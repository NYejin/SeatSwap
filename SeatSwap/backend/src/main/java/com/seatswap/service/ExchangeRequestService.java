package com.seatswap.service;

import com.seatswap.domain.ExchangeRequest;
import com.seatswap.domain.ExchangeWantRange;
import com.seatswap.domain.ExchangeWantSession;
import com.seatswap.domain.ExtraType;
import com.seatswap.domain.PerformanceSession;
import com.seatswap.domain.SeatKey;
import com.seatswap.domain.Ticket;
import com.seatswap.dto.request.ExchangeRequestCreateRequest;
import com.seatswap.dto.request.ExchangeRequestUpdateRequest;
import com.seatswap.dto.request.WantSessionInput;
import com.seatswap.dto.response.ExchangeRequestResponse;
import com.seatswap.exception.BusinessRuleException;
import com.seatswap.exception.ConflictException;
import com.seatswap.exception.FieldValidationException;
import com.seatswap.exception.ForbiddenException;
import com.seatswap.exception.NotFoundException;
import com.seatswap.repository.ExchangeRequestRepository;
import com.seatswap.repository.ExchangeWantRangeRepository;
import com.seatswap.repository.ExchangeWantSeatRepository;
import com.seatswap.repository.ExchangeWantSessionRepository;
import com.seatswap.repository.PerformanceSessionRepository;
import com.seatswap.repository.TicketRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 교환 희망 조건 등록·조회·수정·삭제 (설계 1.2~1.5). 모두 로그인 사용자 본인 기준이다.
 * - 티켓당 요청 1개: 등록은 티켓 행 FOR UPDATE 로 직렬화한 뒤 사전 조회로 409, 그래도 새는 동시 요청은
 *   uk_exchange_request_ticket 위반을 같은 409 로 바꾼다.
 * - 수정·삭제는 요청 행 FOR UPDATE 로 직렬화한다. 요청 행 잠금이 트랜잭션의 첫 쿼리여야 한다
 *   (MySQL REPEATABLE READ 는 첫 일관 읽기에서 스냅샷이 고정된다).
 * - 수정은 범위·희망 좌석·희망 회차를 전부 지우고 다시 만든다(한 트랜잭션). 겹치는 범위는 합집합이다.
 * - 권한: 남의 티켓/요청은 403, 없는 id는 404.
 * - 잠금 순서는 항상 티켓 -> 요청이다(TicketService.deactivate 와 같은 순서). 요청 행만 잠그는 경로(update·delete)에서는
 *   티켓 행을 잠그지 않는다(티켓은 잠금 없는 일반 읽기). 이 규칙을 깨면 deactivate 와 교착이 난다.
 * - 시계는 SessionTimePolicy(Clock 하나) 만 쓴다. 마감(회차 당일 끝) 정책도 티켓 등록과 같다.
 */
@Slf4j
@Service
public class ExchangeRequestService {

    static final String TICKET_NOT_FOUND_MESSAGE = "티켓을 찾을 수 없습니다.";
    static final String REQUEST_NOT_FOUND_MESSAGE = "교환 요청을 찾을 수 없습니다.";
    static final String FORBIDDEN_MESSAGE = "본인의 티켓과 교환 요청만 다룰 수 있습니다.";
    static final String REQUEST_ALREADY_EXISTS_CODE = "REQUEST_ALREADY_EXISTS";
    static final String REQUEST_ALREADY_EXISTS_MESSAGE = "이 티켓에는 이미 교환 요청이 있습니다. 기존 요청을 수정해주세요.";
    static final String TICKET_NOT_ACTIVE_CODE = "TICKET_NOT_ACTIVE";
    static final String TICKET_NOT_ACTIVE_MESSAGE = "내린 티켓에는 교환 요청을 등록하거나 수정할 수 없습니다.";
    static final String OWN_SEAT_CODE = "WANT_INCLUDES_OWN_SEAT";
    static final String OWN_SEAT_MESSAGE =
            "내 티켓과 같은 회차만 희망하면서 희망 좌석에 내 좌석이 포함되어 있습니다. 내 좌석을 제외하거나 다른 회차를 함께 선택해주세요.";
    static final String TICKET_SESSION_CLOSED_CODE = "SESSION_CLOSED";
    static final String TICKET_SESSION_CLOSED_MESSAGE = "회차 당일이 지난 티켓에는 교환 요청을 등록하거나 수정할 수 없습니다.";
    static final String WANT_SESSION_CLOSED_MESSAGE = "이미 마감된 회차는 희망 회차로 선택할 수 없습니다.";
    static final String SESSION_NOT_FOUND_MESSAGE = "존재하지 않는 회차입니다.";
    static final String SESSION_OTHER_PERFORMANCE_MESSAGE = "내 티켓과 같은 공연의 회차만 선택할 수 있습니다.";
    static final String SESSION_DUPLICATE_MESSAGE = "중복된 회차입니다.";
    static final String EXTRA_TYPE_MESSAGE = "추가금 유형은 X, ANY, POS, NEG 중 하나여야 합니다.";

    private final ExchangeRequestRepository requestRepository;
    private final ExchangeWantRangeRepository rangeRepository;
    private final ExchangeWantSessionRepository wantSessionRepository;
    private final ExchangeWantSeatRepository seatRepository;
    private final TicketRepository ticketRepository;
    private final PerformanceSessionRepository sessionRepository;
    private final TransactionTemplate transactionTemplate;
    private final SessionTimePolicy timePolicy;
    private final WantSeatExpander expander;

    public ExchangeRequestService(ExchangeRequestRepository requestRepository,
                                  ExchangeWantRangeRepository rangeRepository,
                                  ExchangeWantSessionRepository wantSessionRepository,
                                  ExchangeWantSeatRepository seatRepository,
                                  TicketRepository ticketRepository,
                                  PerformanceSessionRepository sessionRepository,
                                  PlatformTransactionManager transactionManager,
                                  SessionTimePolicy timePolicy,
                                  @Value("${exchange.want.max-seats:5000}") int maxSeats,
                                  @Value("${exchange.want.max-ranges:50}") int maxRanges,
                                  @Value("${ticket.max-row-number:999}") int maxRowNumber,
                                  @Value("${ticket.max-col-number:999}") int maxColNumber) {
        this.requestRepository = requestRepository;
        this.rangeRepository = rangeRepository;
        this.wantSessionRepository = wantSessionRepository;
        this.seatRepository = seatRepository;
        this.ticketRepository = ticketRepository;
        this.sessionRepository = sessionRepository;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
        this.timePolicy = timePolicy;
        this.expander = new WantSeatExpander(maxRanges, maxSeats, maxRowNumber, maxColNumber);
    }

    /** 입력 검증(DB 불필요)을 마친 값. */
    private record Parsed(ExtraType extraType, Integer extraAmount, WantSeatExpander.Result expanded,
                          List<WantSessionInput> wantSessions) {}

    private record SessionPlan(PerformanceSession session, int priority) {}

    public ExchangeRequestResponse create(Long userId, ExchangeRequestCreateRequest request) {
        Parsed parsed = parse(request.asUpdate());
        try {
            return transactionTemplate.execute(status -> {
                // 주의: 티켓 행 잠금(FOR UPDATE)이 이 트랜잭션의 첫 쿼리여야 한다 (TicketService.create 와 같은 이유).
                Ticket locked = ticketRepository.findByIdForUpdate(request.ticketId())
                        .orElseThrow(() -> new NotFoundException(TICKET_NOT_FOUND_MESSAGE));
                if (!locked.isOwnedBy(userId)) {
                    throw new ForbiddenException(FORBIDDEN_MESSAGE);
                }
                if (!locked.isActive()) {
                    throw new BusinessRuleException(TICKET_NOT_ACTIVE_CODE, TICKET_NOT_ACTIVE_MESSAGE);
                }
                if (requestRepository.existsByTicket_Id(locked.getId())) {
                    throw requestAlreadyExists();
                }
                Ticket ticket = ticketRepository.findWithSessionById(locked.getId()).orElse(locked);
                ensureTicketSessionOpen(ticket);
                List<SessionPlan> sessions = resolveSessions(ticket, parsed.wantSessions());
                ensureNotOwnSeat(ticket, parsed.expanded(), sessions);

                ExchangeRequest saved = requestRepository.saveAndFlush(
                        ExchangeRequest.create(ticket, parsed.extraType(), parsed.extraAmount()));
                return writeChildren(saved, parsed, sessions);
            });
        } catch (DataIntegrityViolationException e) {
            if (!DataIntegrityViolations.isViolationOf(e, ExchangeRequest.UNIQUE_TICKET)) {
                throw e;
            }
            log.info("Exchange request create race on ticket {}", request.ticketId());
            throw requestAlreadyExists();
        }
    }

    /** 내 요청 목록. ticketId 가 있으면 그 티켓의 요청만(내 티켓이 아니면 빈 목록). */
    @Transactional(readOnly = true)
    public List<ExchangeRequestResponse> listMine(Long userId, Long ticketId) {
        List<ExchangeRequest> requests = ticketId == null
                ? requestRepository.findByOwner(userId)
                : requestRepository.findByOwnerAndTicket(userId, ticketId);
        if (requests.isEmpty()) {
            return List.of();
        }
        List<Long> ids = requests.stream().map(ExchangeRequest::getId).toList();
        Map<Long, List<ExchangeWantRange>> ranges = rangeRepository
                .findByRequestIdInOrderByRequestIdAscSortOrderAsc(ids).stream()
                .collect(Collectors.groupingBy(ExchangeWantRange::getRequestId));
        Map<Long, List<ExchangeWantSessionRepository.Row>> sessions = wantSessionRepository.findRows(ids).stream()
                .collect(Collectors.groupingBy(ExchangeWantSessionRepository.Row::requestId));
        Map<Long, Integer> counts = seatRepository.countByRequestIds(ids);

        List<ExchangeRequestResponse> result = new ArrayList<>(requests.size());
        for (ExchangeRequest r : requests) {
            result.add(toResponse(r,
                    ranges.getOrDefault(r.getId(), List.of()),
                    sessions.getOrDefault(r.getId(), List.of()).stream()
                            .map(row -> new ExchangeRequestResponse.WantSessionItem(
                                    row.sessionId(), row.priority(), row.startsAt()))
                            .toList(),
                    counts.getOrDefault(r.getId(), 0)));
        }
        return result;
    }

    /** 범위·희망 회차·추가금을 통째로 교체한다. 요청 행 FOR UPDATE 로 같은 요청의 동시 수정을 직렬화한다. */
    @Transactional
    public ExchangeRequestResponse update(Long userId, Long requestId, ExchangeRequestUpdateRequest request) {
        Parsed parsed = parse(request);
        // 요청 행 잠금이 이 트랜잭션의 첫 쿼리여야 한다 (잠금 대기 뒤에 최신 상태를 읽기 위해).
        ExchangeRequest locked = requestRepository.findByIdForUpdate(requestId)
                .orElseThrow(() -> new NotFoundException(REQUEST_NOT_FOUND_MESSAGE));
        Ticket ticket = ticketRepository.findWithSessionById(locked.getTicket().getId())
                .orElseThrow(() -> new NotFoundException(TICKET_NOT_FOUND_MESSAGE));
        if (!ticket.isOwnedBy(userId)) {
            throw new ForbiddenException(FORBIDDEN_MESSAGE);
        }
        if (!ticket.isActive() || !locked.isOpen()) {
            throw new BusinessRuleException(TICKET_NOT_ACTIVE_CODE, TICKET_NOT_ACTIVE_MESSAGE);
        }
        ensureNoActiveProposal(locked);
        ensureTicketSessionOpen(ticket);
        List<SessionPlan> sessions = resolveSessions(ticket, parsed.wantSessions());
        ensureNotOwnSeat(ticket, parsed.expanded(), sessions);

        seatRepository.deleteByRequestId(locked.getId());
        rangeRepository.deleteByRequestId(locked.getId());
        wantSessionRepository.deleteByRequestId(locked.getId());
        locked.applyExtra(parsed.extraType(), parsed.extraAmount());
        locked.touch(timePolicy.now());
        requestRepository.saveAndFlush(locked);
        return writeChildren(locked, parsed, sessions);
    }

    /** 하드 삭제. 파생 테이블(range·seat·session)은 DB ON DELETE CASCADE 로 함께 지워진다. */
    @Transactional
    public void delete(Long userId, Long requestId) {
        ExchangeRequest locked = requestRepository.findByIdForUpdate(requestId)
                .orElseThrow(() -> new NotFoundException(REQUEST_NOT_FOUND_MESSAGE));
        if (!locked.getTicket().isOwnedBy(userId)) {
            throw new ForbiddenException(FORBIDDEN_MESSAGE);
        }
        ensureNoActiveProposal(locked);
        requestRepository.delete(locked);
        requestRepository.flush();
    }

    /**
     * 수정·삭제 전 '진행 중인 제안(채팅·예약)이 있는가' 검사 자리. 매칭 테이블(exchange_match 등)이 아직 없어
     * 지금은 항상 통과한다. 매칭 구현 때 열린 매칭이 있으면 409 ConflictException 을 던지도록 이 메서드만 채운다.
     */
    void ensureNoActiveProposal(ExchangeRequest request) {
    }

    // ---------------------------------------------------------------- 입력 검증

    private Parsed parse(ExchangeRequestUpdateRequest request) {
        Map<String, String> errors = new LinkedHashMap<>();
        ExtraType type = parseExtraType(errors, request.extraType(), request.extraAmount());
        if (!errors.isEmpty()) {
            // 추가금 오류가 있어도 범위 오류를 함께 보여주기 위해 범위 검증을 이어서 한다.
            try {
                expander.expand(request.ranges());
            } catch (FieldValidationException e) {
                errors.putAll(e.getErrors());
            }
            throw FieldValidationException.ofAll(errors);
        }
        WantSeatExpander.Result expanded = expander.expand(request.ranges());
        return new Parsed(type, request.extraAmount(), expanded, request.wantSessions());
    }

    private static ExtraType parseExtraType(Map<String, String> errors, String raw, Integer amount) {
        ExtraType type;
        try {
            type = raw == null ? null : ExtraType.valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            type = null;
        }
        if (type == null) {
            errors.put("extraType", EXTRA_TYPE_MESSAGE);
            return null;
        }
        switch (type) {
            case X, ANY -> {
                if (amount != null) {
                    errors.put("extraAmount", "추가금 X/상관없음에서는 금액을 입력할 수 없습니다.");
                }
            }
            case POS -> {
                if (amount == null || amount <= 0) {
                    errors.put("extraAmount", "받을 금액은 0보다 큰 금액을 입력해주세요.");
                }
            }
            case NEG -> {
                if (amount == null || amount >= 0) {
                    errors.put("extraAmount", "낼 금액은 0보다 작은 금액(예: -10000)을 입력해주세요.");
                }
            }
        }
        return type;
    }

    /** 희망 회차: 존재해야 하고, 내 티켓과 같은 공연이어야 하며, 중복이 없어야 한다 (본인 티켓의 회차도 가능). */
    private List<SessionPlan> resolveSessions(Ticket ticket, List<WantSessionInput> inputs) {
        Long performanceId = ticket.getPerformanceSession().getPerformance().getId();
        List<Long> ids = inputs.stream().map(WantSessionInput::sessionId).distinct().toList();
        Map<Long, PerformanceSession> found = sessionRepository.findAllById(ids).stream()
                .collect(Collectors.toMap(PerformanceSession::getId, Function.identity()));

        Map<String, String> errors = new LinkedHashMap<>();
        Set<Long> seen = new HashSet<>();
        List<SessionPlan> plans = new ArrayList<>(inputs.size());
        for (int i = 0; i < inputs.size(); i++) {
            WantSessionInput in = inputs.get(i);
            String field = "wantSessions[" + i + "].sessionId";
            PerformanceSession session = found.get(in.sessionId());
            if (session == null) {
                errors.put(field, SESSION_NOT_FOUND_MESSAGE);
            } else if (!performanceId.equals(session.getPerformance().getId())) {
                errors.put(field, SESSION_OTHER_PERFORMANCE_MESSAGE);
            } else if (!timePolicy.now().isBefore(session.registrationDeadline())) {
                errors.put(field, WANT_SESSION_CLOSED_MESSAGE);
            } else if (!seen.add(session.getId())) {
                errors.put(field, SESSION_DUPLICATE_MESSAGE);
            } else {
                plans.add(new SessionPlan(session, in.priority()));
            }
        }
        if (!errors.isEmpty()) {
            throw FieldValidationException.ofAll(errors);
        }
        return plans;
    }

    /** 티켓 등록과 같은 정책: 회차 당일 끝(다음날 0시 KST)이 지나면 그 티켓의 교환 요청은 등록·수정할 수 없다. */
    private void ensureTicketSessionOpen(Ticket ticket) {
        if (!timePolicy.now().isBefore(ticket.getPerformanceSession().registrationDeadline())) {
            throw new BusinessRuleException(TICKET_SESSION_CLOSED_CODE, TICKET_SESSION_CLOSED_MESSAGE);
        }
    }

    /**
     * 희망 회차가 내 티켓의 회차 하나뿐일 때만 내 좌석 포함을 막는다. 같은 회차에서는 같은 좌석의 활성 티켓이 1개
     * (uk_ticket_active_seat)이고 자기 자신과는 매칭되지 않아 성립할 수 없지만, 다른 회차의 같은 자리는 교환할 수 있다.
     */
    private static void ensureNotOwnSeat(Ticket ticket, WantSeatExpander.Result expanded, List<SessionPlan> sessions) {
        boolean onlyMySession = sessions.size() == 1
                && sessions.get(0).session().getId().equals(ticket.getPerformanceSession().getId());
        if (onlyMySession
                && expanded.seats().contains(new SeatKey(ticket.getZoneKey(), ticket.getRowKey(), ticket.getColKey()))) {
            throw new BusinessRuleException(OWN_SEAT_CODE, OWN_SEAT_MESSAGE);
        }
    }

    // ---------------------------------------------------------------- 저장·응답

    /** 요청 행이 저장·flush 된 뒤 범위·희망 회차·펼친 좌석을 저장하고 응답을 만든다. */
    private ExchangeRequestResponse writeChildren(ExchangeRequest request, Parsed parsed, List<SessionPlan> sessions) {
        Long requestId = request.getId();
        List<ExchangeWantRange> ranges = new ArrayList<>();
        int order = 0;
        for (WantSeatExpander.Range r : parsed.expanded().ranges()) {
            ranges.add(ExchangeWantRange.create(requestId, r.zoneLabel(), r.zoneKey(),
                    r.rowFrom(), r.rowTo(), r.colFrom(), r.colTo(), order++));
        }
        rangeRepository.saveAll(ranges);
        wantSessionRepository.saveAll(sessions.stream()
                .map(p -> ExchangeWantSession.create(requestId, p.session().getId(), p.priority()))
                .toList());
        rangeRepository.flush();
        seatRepository.insertAll(requestId, parsed.expanded().seats());

        List<ExchangeRequestResponse.WantSessionItem> items = sessions.stream()
                .sorted(Comparator.comparingInt(SessionPlan::priority)
                        .thenComparing(p -> p.session().getStartsAt())
                        .thenComparing(p -> p.session().getId()))
                .map(p -> new ExchangeRequestResponse.WantSessionItem(
                        p.session().getId(), p.priority(), p.session().getStartsAt()))
                .toList();
        return toResponse(request, ranges, items, parsed.expanded().seats().size());
    }

    private static ExchangeRequestResponse toResponse(ExchangeRequest r, List<ExchangeWantRange> ranges,
                                                      List<ExchangeRequestResponse.WantSessionItem> sessions,
                                                      int seatCount) {
        return new ExchangeRequestResponse(
                r.getId(),
                r.getTicket().getId(),
                r.getStatus().name(),
                r.getExtraType().name(),
                r.getExtraAmount(),
                sessions,
                ranges.stream().map(w -> new ExchangeRequestResponse.WantRangeItem(
                        w.getZoneLabel(), w.getRowFrom(), w.getRowTo(), w.getColFrom(), w.getColTo())).toList(),
                seatCount,
                r.getCreatedAt(),
                r.getUpdatedAt());
    }

    private static ConflictException requestAlreadyExists() {
        return new ConflictException(REQUEST_ALREADY_EXISTS_MESSAGE, Map.of("code", REQUEST_ALREADY_EXISTS_CODE));
    }
}
