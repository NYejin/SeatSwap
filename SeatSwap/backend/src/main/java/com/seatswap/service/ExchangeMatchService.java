package com.seatswap.service;

import com.seatswap.domain.ExchangeMatch;
import com.seatswap.domain.ExchangeMatch.Side;
import com.seatswap.domain.ExchangeMatchAction;
import com.seatswap.domain.ExchangeMatchStatus;
import com.seatswap.domain.ExchangeRequest;
import com.seatswap.domain.Ticket;
import com.seatswap.dto.response.ExchangeMatchResponse;
import com.seatswap.exception.BusinessRuleException;
import com.seatswap.exception.ConflictException;
import com.seatswap.exception.ForbiddenException;
import com.seatswap.exception.NotFoundException;
import com.seatswap.repository.ExchangeCandidateRepository;
import com.seatswap.repository.ExchangeMatchQueryRepository;
import com.seatswap.repository.ExchangeMatchRepository;
import com.seatswap.repository.ExchangeRequestRepository;
import com.seatswap.repository.ExchangeTicketLockRepository;
import com.seatswap.repository.TicketRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * 매칭 생성(후보 선택 -> 채팅 시작)과 예약(양쪽 동의)·거절·취소 (설계 1.7, 1.8, 3절).
 * 매칭은 조건 일치 판정으로 찾은 후보를 사용자가 골라 시작하고 점수화·랭킹·신뢰도는 쓰지 않는다. 흐름은
 * 후보 선택 -> 채팅(CHATTING, 한 요청에 여러 개 동시) -> 양쪽 '이 사람과 교환할게요'(RESERVED, 두 티켓 잠금, 티켓당 1개)
 * -> (이번 범위 밖) 양도 후 각자 교환 완료 -> COMPLETED. 취소는 양쪽 완료 전 누구나 가능하고 상태만 원상태로 돌아간다(재매칭 불가 아님).
 *
 * <h3>허용/불허 상태 전이 표 ({@link ExchangeMatch#isAllowed})</h3>
 * <pre>
 *            PROPOSE  ACCEPT      REJECT(b측)  CANCEL  COMPLETE
 * CHATTING   409      허용        허용         허용    409 (예약 전)
 * RESERVED   409      멱등 200    허용         허용    허용 (미구현)
 * COMPLETED  허용*    409         409          409     409
 * CANCELED   허용*    409         409          409     409
 * </pre>
 * PROPOSE 는 '같은 요청 쌍의 열린 매칭이 있는가'가 기준이다(*: 열린 매칭이 없으면, 취소·완료된 뒤에는 새 매칭을 만들 수 있다).
 * 불허 전이는 409 {message, code: MATCH_STATE_CONFLICT, status, action}. PROPOSE 중복은 409 MATCH_ALREADY_OPEN(+matchId).
 *
 * <h3>동작과 오류</h3>
 * <ul>
 *   <li>propose: 내 요청에서 후보를 골라 CHATTING 매칭을 만든다(201). 남의 요청 403, 없는 요청 404, 내 요청이 삭제됐으면 409 REQUEST_DELETED, 내 요청이 닫힘/내린 티켓
 *       422 TICKET_NOT_ACTIVE, 내 회차 마감 422 SESSION_CLOSED, 후보 조건 불충족
 *       (자기 자신·같은 사용자·상대 요청 닫힘/삭제·좌석/회차/추가금 불일치) 422 NOT_A_CANDIDATE. 검증 순서는 후보 판정이 먼저이고 그 뒤 잠금 검사다:
 *       내 티켓이 예약 잠금이면 422 TICKET_LOCKED, 상대 티켓이 잠겼으면 후보에서 빠진 것과 같게 NOT_A_CANDIDATE(상대 티켓의 예약 상태를 노출하지 않는다). 제안 시점에 후보 SQL과 같은 판정을 쌍 단위로 다시 하고,
 *       그때 읽은 양쪽 적용 추가금(범위 단위)을 매칭 행의 스냅샷(a/b_extra_type·amount)으로 복사한다.</li>
 *   <li>accept: 호출자 쪽 예약 동의. 한쪽만 누르면 CHATTING 유지(누른 시각 a/b_reserved_at), 양쪽이 누르면 RESERVED 로 바뀌고
 *       두 티켓에 exchange_ticket_lock 을 한 트랜잭션에서 INSERT 한다. 이미 눌렀다면 멱등 200. 어느 티켓이든 이미 다른 매칭에서
 *       잠겼으면 409 TICKET_ALREADY_RESERVED (같은 티켓의 두 매칭이 동시에 완료하려 하면 하나만 RESERVED).</li>
 *   <li>reject: 제안받은 쪽(b)이 거절. cancel: 참여자 누구나 취소. 둘 다 결과는 CANCELED 이고 RESERVED 였다면 잠금을 푼다
 *       (canceled_by = 호출자). reject 를 제안자(a)가 부르면 403. 매칭 참여자가 아니면 없는 매칭과 같은 404(존재 은닉).</li>
 * </ul>
 *
 * <h3>동시성: 요청 행 잠금 기반</h3>
 * 잠금 순서는 항상 티켓(id 오름차순) -> 요청(id 오름차순) -> 매칭이다(TicketService.deactivate·ExchangeRequestService 와 같은 규약).
 * 어떤 연산이든 같은 순서로 잡으므로 교착이 없고, 같은 쌍의 동시 propose 는 직렬화되어 하나만 201 이고 나머지는 열린 매칭을 보고 409 이다.
 * 잠금 읽기(FOR UPDATE) 이후에야 첫 일관 읽기(스냅샷)가 시작되도록, 요청·매칭의 불변 컬럼(티켓 id·사용자 id)을 먼저 트랜잭션 밖에서 읽는다.
 * 최종 방어선은 DB 제약이다: uk_exchange_match_open_pair(같은 쌍 열린 매칭 1개), exchange_ticket_lock PK(티켓당 예약 1개).
 *
 * <h3>COMPLETED(이번 범위 밖)와 티켓 처리 규칙 (8차 답변, 2026-10-09, 구현 예정)</h3>
 * <b>COMPLETED 시점(양쪽이 '교환 완료'를 누르는 순간)에 한 트랜잭션에서 기존 두 티켓을 EXCHANGED 로 바꾸고 각자 새 자리 티켓을 INSERT</b>한다. 한쪽만 완료했을 때
 * 먼저 처리하면 안 되므로 그 전에는 완료 시각만 기록한다. 기존 티켓을 먼저 EXCHANGED 로 바꿔 uk_ticket_active_seat 에서 빼므로 임시 INACTIVE 순서는 필요 없다.
 * 이력 스냅샷 2행(old_ticket_id/new_ticket_id)을 남기고, 같은 티켓들의 다른 열린 매칭은 시스템 취소하고 잠금을 푼다. 채팅 메시지·교환 이력·차단은 후속이다.
 *
 * 공연 시작 후에도 예약·취소가 가능하다. 이미 시작한 채팅의 accept 에는 회차 마감 검사를 하지 않는다(새 매칭 생성에만 적용).
 */
@Slf4j
@Service
public class ExchangeMatchService {

    static final String REQUEST_NOT_FOUND_MESSAGE = "교환 요청을 찾을 수 없습니다.";
    static final String MATCH_NOT_FOUND_MESSAGE = "매칭을 찾을 수 없습니다.";
    static final String FORBIDDEN_REQUEST_MESSAGE = "본인의 교환 요청으로만 매칭을 시작할 수 있습니다.";
    static final String FORBIDDEN_MATCH_MESSAGE = "매칭 참여자만 사용할 수 있습니다.";
    static final String FORBIDDEN_REJECT_MESSAGE = "제안받은 쪽만 거절할 수 있습니다. 제안한 쪽은 취소를 사용해주세요.";
    static final String NOT_ACTIVE_CODE = "TICKET_NOT_ACTIVE";
    static final String NOT_ACTIVE_MESSAGE = "내린 티켓이나 닫힌 요청으로는 매칭을 시작할 수 없습니다.";
    static final String SESSION_CLOSED_CODE = "SESSION_CLOSED";
    static final String SESSION_CLOSED_MESSAGE = "회차 당일이 지난 티켓으로는 매칭을 시작할 수 없습니다.";
    static final String TICKET_LOCKED_CODE = "TICKET_LOCKED";
    static final String TICKET_LOCKED_MESSAGE = "이미 교환이 예약된 티켓이 포함되어 있어 매칭을 시작할 수 없습니다.";
    static final String NOT_A_CANDIDATE_CODE = "NOT_A_CANDIDATE";
    static final String NOT_A_CANDIDATE_MESSAGE = "서로의 교환 조건이 맞는 상대가 아니어서 매칭을 시작할 수 없습니다. 후보 목록을 새로고침해주세요.";
    static final String MATCH_ALREADY_OPEN_CODE = "MATCH_ALREADY_OPEN";
    static final String MATCH_ALREADY_OPEN_MESSAGE = "이 상대와 이미 진행 중인 매칭이 있습니다.";
    static final String STATE_CONFLICT_CODE = "MATCH_STATE_CONFLICT";
    static final String ALREADY_RESERVED_CODE = "TICKET_ALREADY_RESERVED";
    static final String ALREADY_RESERVED_MESSAGE = "이 매칭의 티켓이 이미 다른 매칭에서 예약되어 있어 예약할 수 없습니다.";

    private final ExchangeMatchRepository matchRepository;
    private final ExchangeRequestRepository requestRepository;
    private final TicketRepository ticketRepository;
    private final ExchangeTicketLockRepository lockRepository;
    private final ExchangeCandidateRepository candidateRepository;
    private final ExchangeMatchQueryRepository queryRepository;
    private final SessionTimePolicy timePolicy;
    private final TransactionTemplate tx;

    public ExchangeMatchService(ExchangeMatchRepository matchRepository,
                                ExchangeRequestRepository requestRepository,
                                TicketRepository ticketRepository,
                                ExchangeTicketLockRepository lockRepository,
                                ExchangeCandidateRepository candidateRepository,
                                ExchangeMatchQueryRepository queryRepository,
                                SessionTimePolicy timePolicy,
                                PlatformTransactionManager transactionManager) {
        this.matchRepository = matchRepository;
        this.requestRepository = requestRepository;
        this.ticketRepository = ticketRepository;
        this.lockRepository = lockRepository;
        this.candidateRepository = candidateRepository;
        this.queryRepository = queryRepository;
        this.timePolicy = timePolicy;
        this.tx = new TransactionTemplate(transactionManager);
    }

    // ---------------------------------------------------------------- 제안(매칭 생성)

    public ExchangeMatchResponse propose(Long userId, Long myRequestId, Long targetRequestId) {
        // 트랜잭션 밖의 읽기: 요청이 가리키는 티켓은 바뀌지 않으므로(updatable=false, uk) 잠금 대상을 미리 알 수 있다.
        Long myTicketId = requestRepository.findTicketIdById(myRequestId)
                .orElseThrow(() -> new NotFoundException(REQUEST_NOT_FOUND_MESSAGE));
        Long ownerId = ticketRepository.findOwnerIdById(myTicketId)
                .orElseThrow(() -> new NotFoundException(ExchangeRequestService.TICKET_NOT_FOUND_MESSAGE));
        if (!ownerId.equals(userId)) {
            throw new ForbiddenException(FORBIDDEN_REQUEST_MESSAGE);
        }
        if (myRequestId.equals(targetRequestId)) {
            throw new BusinessRuleException(NOT_A_CANDIDATE_CODE, NOT_A_CANDIDATE_MESSAGE);
        }
        Long targetTicketId = requestRepository.findTicketIdById(targetRequestId)
                .orElseThrow(() -> new NotFoundException(REQUEST_NOT_FOUND_MESSAGE));

        ExchangeMatchResponse response;
        try {
            response = tx.execute(status -> {
                // 1) 티켓(id 오름차순) 2) 요청(id 오름차순) 잠금. 잠금 읽기가 이 트랜잭션의 첫 쿼리들이어야 한다.
                Map<Long, Ticket> tickets = lockTickets(List.of(myTicketId, targetTicketId));
                Map<Long, ExchangeRequest> requests = lockRequests(List.of(myRequestId, targetRequestId));
                ExchangeRequest myRequest = requests.get(myRequestId);
                ExchangeRequest target = requests.get(targetRequestId);
                Ticket myTicket = tickets.get(myTicketId);
                Ticket targetTicket = tickets.get(targetTicketId);

                // 같은 쌍의 열린 매칭 -> 409 (사용자가 이미 채팅 중인 상대를 다시 고른 경우)
                List<ExchangeMatch> open = matchRepository.findOpenByPair(myRequestId, targetRequestId);
                if (!open.isEmpty()) {
                    throw new ConflictException(MATCH_ALREADY_OPEN_MESSAGE,
                            Map.of("code", MATCH_ALREADY_OPEN_CODE, "matchId", open.get(0).getId()));
                }
                // 내 요청이 삭제됐으면 후보 조회·수정과 같은 409 REQUEST_DELETED (상대 요청이 삭제됐으면 아래에서 NOT_A_CANDIDATE)
                if (myRequest.isDeleted()) {
                    throw new ConflictException(ExchangeRequestService.REQUEST_DELETED_MESSAGE,
                            Map.of("code", ExchangeRequestService.REQUEST_DELETED_CODE));
                }
                if (!myTicket.isActive() || !myRequest.isOpen()) {
                    throw new BusinessRuleException(NOT_ACTIVE_CODE, NOT_ACTIVE_MESSAGE);
                }
                Ticket withSession = ticketRepository.findWithSessionById(myTicketId).orElse(myTicket);
                LocalDateTime now = timePolicy.now();
                if (!now.isBefore(withSession.getPerformanceSession().registrationDeadline())) {
                    throw new BusinessRuleException(SESSION_CLOSED_CODE, SESSION_CLOSED_MESSAGE);
                }
                // 후보 판정이 먼저다: 후보가 아닌 상대의 티켓 예약 상태는 어떤 응답으로도 드러내지 않는다.
                ExchangeCandidateRepository.PairExtras extras = (!target.isOpen() || !targetTicket.isActive())
                        ? null
                        : candidateRepository.findCandidatePair(myRequestId, targetRequestId, now.toLocalDate().atStartOfDay())
                                .orElse(null);
                if (extras == null) {
                    throw new BusinessRuleException(NOT_A_CANDIDATE_CODE, NOT_A_CANDIDATE_MESSAGE);
                }
                // 내 티켓이 잠긴 경우만 TICKET_LOCKED 로 구분한다. 상대 티켓이 잠겼으면 후보에서 빠진 것과 같게 NOT_A_CANDIDATE.
                if (lockRepository.existsAnyByTicketIds(List.of(myTicketId))) {
                    throw new BusinessRuleException(TICKET_LOCKED_CODE, TICKET_LOCKED_MESSAGE);
                }
                if (lockRepository.existsAnyByTicketIds(List.of(targetTicketId))) {
                    throw new BusinessRuleException(NOT_A_CANDIDATE_CODE, NOT_A_CANDIDATE_MESSAGE);
                }
                ExchangeMatch saved = matchRepository.saveAndFlush(ExchangeMatch.propose(myRequestId, targetRequestId,
                        myTicketId, targetTicketId, myTicket.getUser().getId(), targetTicket.getUser().getId(),
                        extras.my(), extras.their()));
                return view(saved.getId(), userId);
            });
        } catch (DataIntegrityViolationException e) {
            // 안전망: 요청 행 잠금으로 직렬화되어 정상 경로에서는 도달하지 않는다(uk_exchange_match_open_pair 최후 방어선).
            if (!DataIntegrityViolations.isViolationOf(e, ExchangeMatch.UNIQUE_OPEN_PAIR)) {
                throw e;
            }
            log.info("Exchange match propose race on pair ({}, {})", myRequestId, targetRequestId);
            throw new ConflictException(MATCH_ALREADY_OPEN_MESSAGE, Map.of("code", MATCH_ALREADY_OPEN_CODE));
        }
        return response;
    }

    // ---------------------------------------------------------------- 예약 동의·거절·취소

    /** 호출자 쪽 예약 동의('이 사람과 교환할게요'). 양쪽이 모두 누르면 RESERVED + 두 티켓 잠금. 이미 눌렀다면 멱등 200. */
    public ExchangeMatchResponse accept(Long userId, Long matchId) {
        return act(userId, matchId, ExchangeMatchAction.ACCEPT);
    }

    /** 제안받은 쪽(b)의 거절 -> CANCELED. RESERVED 였다면 잠금 해제. */
    public ExchangeMatchResponse reject(Long userId, Long matchId) {
        return act(userId, matchId, ExchangeMatchAction.REJECT);
    }

    /** 참여자 누구나 양쪽 완료 전에 취소 -> CANCELED. RESERVED 였다면 잠금 해제. */
    public ExchangeMatchResponse cancel(Long userId, Long matchId) {
        return act(userId, matchId, ExchangeMatchAction.CANCEL);
    }

    private ExchangeMatchResponse act(Long userId, Long matchId, ExchangeMatchAction action) {
        // 트랜잭션 밖의 읽기: 티켓·요청·사용자 id 는 바뀌지 않으므로 잠금 대상과 참여 여부를 미리 확정할 수 있다.
        ExchangeMatch pre = matchRepository.findById(matchId)
                .orElseThrow(() -> new NotFoundException(MATCH_NOT_FOUND_MESSAGE));
        Side side = pre.sideOf(userId);
        if (side == null) {
            // 비참여자에게는 매칭의 존재 여부를 드러내지 않는다(티켓 존재 은닉 정책과 일관): 없는 매칭과 같은 404.
            throw new NotFoundException(MATCH_NOT_FOUND_MESSAGE);
        }
        if (action == ExchangeMatchAction.REJECT && side != Side.B) {
            throw new ForbiddenException(FORBIDDEN_REJECT_MESSAGE);
        }

        ExchangeMatchResponse result;
        try {
            result = tx.execute(status -> {
                lockTickets(List.of(pre.getTicketAId(), pre.getTicketBId()));
                lockRequests(List.of(pre.getRequestAId(), pre.getRequestBId()));
                ExchangeMatch m = matchRepository.findByIdForUpdate(matchId)
                        .orElseThrow(() -> new NotFoundException(MATCH_NOT_FOUND_MESSAGE));
                if (!ExchangeMatch.isAllowed(m.getStatus(), action)) {
                    throw stateConflict(m.getStatus(), action);
                }
                LocalDateTime now = timePolicy.now();
                switch (action) {
                    case ACCEPT -> doAccept(m, side, now);
                    case REJECT, CANCEL -> {
                        m.cancel(userId, now);
                        matchRepository.saveAndFlush(m);
                        lockRepository.deleteByMatchId(m.getId());
                    }
                    default -> throw new IllegalStateException("지원하지 않는 동작: " + action);
                }
                return view(m.getId(), userId);
            });
        } catch (DataIntegrityViolationException e) {
            // 안전망: 위 사전 검사를 통과해도 새는 경우의 최후 방어선. exchange_ticket_lock PK 충돌(JdbcTemplate 은 DuplicateKeyException)
            if (e instanceof DuplicateKeyException) {
                throw alreadyReserved();
            }
            throw e;
        }
        return result;
    }

    /** 잠금 순서(티켓 -> 요청 -> 매칭)를 모두 잡은 뒤 호출한다. */
    private void doAccept(ExchangeMatch m, Side side, LocalDateTime now) {
        if (m.hasReserved(side)) {
            return; // 이미 눌렀다 -> 멱등 200 (RESERVED 포함)
        }
        List<Long> ticketIds = List.of(m.getTicketAId(), m.getTicketBId());
        // 티켓당 예약 1개: 어느 티켓이든 다른 매칭에서 이미 잠겼으면 409. (이 매칭이 RESERVED 가 아니므로 이 매칭의 잠금은 없다)
        if (lockRepository.existsAnyByTicketIds(ticketIds)) {
            throw alreadyReserved();
        }
        m.markReserved(side, now);
        if (m.bothReserved()) {
            m.toReserved();
            matchRepository.saveAndFlush(m);
            for (Long ticketId : ticketIds.stream().sorted().toList()) {
                try {
                    lockRepository.insert(ticketId, m.getId(), now);
                } catch (DuplicateKeyException e) {
                    // 안전망: 위 existsAny 검사와 티켓 행 잠금 때문에 정상 경로에서는 도달하지 않는다(PK 최후 방어선).
                    throw alreadyReserved();
                }
            }
        } else {
            matchRepository.saveAndFlush(m);
        }
    }

    // ---------------------------------------------------------------- 도우미

    /** 티켓 행을 id 오름차순으로 잠근다(FOR UPDATE). 없는 티켓이면 404. */
    private Map<Long, Ticket> lockTickets(List<Long> ids) {
        Map<Long, Ticket> locked = new java.util.LinkedHashMap<>();
        for (Long id : ids.stream().distinct().sorted().toList()) {
            locked.put(id, ticketRepository.findByIdForUpdate(id)
                    .orElseThrow(() -> new NotFoundException(ExchangeRequestService.TICKET_NOT_FOUND_MESSAGE)));
        }
        return locked;
    }

    /** 요청 행을 id 오름차순으로 잠근다(FOR UPDATE). 그 사이 삭제됐다면 404. */
    private Map<Long, ExchangeRequest> lockRequests(List<Long> ids) {
        Map<Long, ExchangeRequest> locked = new java.util.LinkedHashMap<>();
        for (Long id : ids.stream().distinct().sorted().toList()) {
            locked.put(id, requestRepository.findByIdForUpdate(id)
                    .orElseThrow(() -> new NotFoundException(REQUEST_NOT_FOUND_MESSAGE)));
        }
        return locked;
    }

    private static ConflictException stateConflict(ExchangeMatchStatus status, ExchangeMatchAction action) {
        return new ConflictException(stateConflictMessage(status),
                Map.of("code", STATE_CONFLICT_CODE, "status", status.name(), "action", action.name()));
    }

    private static String stateConflictMessage(ExchangeMatchStatus status) {
        return switch (status) {
            case CANCELED -> "이미 취소된 매칭입니다.";
            case COMPLETED -> "이미 교환이 완료된 매칭입니다.";
            case CHATTING, RESERVED -> "현재 상태(" + status + ")에서는 처리할 수 없습니다.";
        };
    }

    private static ConflictException alreadyReserved() {
        return new ConflictException(ALREADY_RESERVED_MESSAGE, Map.of("code", ALREADY_RESERVED_CODE));
    }

    /** 호출 전 flush 필수(변경 후 saveAndFlush 로 반영된 상태여야 조인 조회가 읽는다). 응답은 조인 한 번으로 만든다(닉네임·좌석·회차·추가금 포함, 추가 쿼리 없음). 트랜잭션 안에서 호출해 방금 쓴 상태를 그대로 읽는다. */
    private ExchangeMatchResponse view(Long matchId, Long userId) {
        return queryRepository.findOne(matchId, userId)
                .orElseThrow(() -> new NotFoundException(MATCH_NOT_FOUND_MESSAGE))
                .toResponse(userId);
    }
}
