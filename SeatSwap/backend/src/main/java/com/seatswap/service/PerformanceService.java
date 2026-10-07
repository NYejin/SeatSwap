package com.seatswap.service;

import com.seatswap.domain.Performance;
import com.seatswap.domain.PerformanceSession;
import com.seatswap.domain.User;
import com.seatswap.domain.Venue;
import com.seatswap.dto.request.PerformanceCreateRequest;
import com.seatswap.dto.request.PerformanceUpdateRequest;
import com.seatswap.dto.response.PageResponse;
import com.seatswap.dto.response.PerformanceDetailResponse;
import com.seatswap.dto.response.PerformanceLookupResponse;
import com.seatswap.dto.response.PerformanceSummaryResponse;
import com.seatswap.dto.response.SessionResponse;
import com.seatswap.dto.response.VenueResponse;
import com.seatswap.exception.ConflictException;
import com.seatswap.exception.FieldValidationException;
import com.seatswap.exception.NotFoundException;
import com.seatswap.exception.SeatSwapException;
import com.seatswap.repository.PerformanceRepository;
import com.seatswap.repository.PerformanceSessionRepository;
import com.seatswap.repository.TicketRepository;
import com.seatswap.repository.UserRepository;
import com.seatswap.repository.VenueRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.InsufficientAuthenticationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 공연 등록·조회·수정·삭제 (FR-02).
 * 권한: 조회·등록은 로그인 사용자 누구나, 수정·삭제는 등록자만(아니면 AccessDeniedException → 403).
 * 링크에서 공연정보를 읽는 외부 URL 요청은 이 서비스의 책임이 아니다 (후속 작업에서 별도로 둔다)..
 */
@Slf4j
@Service
public class PerformanceService {

    static final int DEFAULT_PAGE_SIZE = 20;
    static final int MAX_PAGE_SIZE = 50;
    static final int QUERY_MAX_LENGTH = 100;
    static final String UNIQUE_SOURCE_KEY = "uk_performance_source_key";

    static final String DUPLICATE_PERFORMANCE_MESSAGE = "이미 등록된 공연입니다.";
    static final String NOT_FOUND_MESSAGE = "공연을 찾을 수 없습니다.";
    static final String FORBIDDEN_MESSAGE = "접근 권한이 없습니다.";
    static final String VENUE_NOT_FOUND_MESSAGE = "존재하지 않는 공연장입니다.";
    static final String VENUE_CHANGE_BLOCKED_MESSAGE = "티켓이 등록된 공연은 공연장을 변경할 수 없습니다.";
    static final String DELETE_BLOCKED_MESSAGE = "티켓이 등록된 공연은 삭제할 수 없습니다.";

    private final PerformanceRepository performanceRepository;
    private final PerformanceSessionRepository sessionRepository;
    private final VenueRepository venueRepository;
    private final UserRepository userRepository;
    private final TicketRepository ticketRepository;
    private final SourceKeyResolver sourceKeyResolver;
    private final SessionTimePolicy sessionTimePolicy;
    private final TransactionTemplate transactionTemplate;

    public PerformanceService(PerformanceRepository performanceRepository,
                              PerformanceSessionRepository sessionRepository,
                              VenueRepository venueRepository,
                              UserRepository userRepository,
                              TicketRepository ticketRepository,
                              SourceKeyResolver sourceKeyResolver,
                              SessionTimePolicy sessionTimePolicy,
                              PlatformTransactionManager transactionManager) {
        this.performanceRepository = performanceRepository;
        this.sessionRepository = sessionRepository;
        this.venueRepository = venueRepository;
        this.userRepository = userRepository;
        this.ticketRepository = ticketRepository;
        this.sourceKeyResolver = sourceKeyResolver;
        this.sessionTimePolicy = sessionTimePolicy;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    /** 링크로 이미 등록된 공연이 있는지 (등록 화면에서 입력 직후 확인용). 형식 오류는 400 {"sourceUrl": ...}. */
    @Transactional(readOnly = true)
    public PerformanceLookupResponse lookup(String sourceUrl) {
        String sourceKey = sourceKeyResolver.resolve(sourceUrl);
        return performanceRepository.findIdBySourceKey(sourceKey)
                .map(PerformanceLookupResponse::found)
                .orElseGet(PerformanceLookupResponse::notFound);
    }

    /**
     * 제목 부분 일치 + 공연장 필터. 다가오는 회차가 있는 공연 먼저(가까운 순). page는 0부터.
     * 대소문자 무시는 컬럼 collation(utf8mb4_0900_ai_ci)에 맡긴다 - lower()를 씌우지 않는다.
     * asOf: 정렬과 nextSessionStartsAt의 기준 시각. 페이지를 넘기는 동안 기준이 바뀌어 항목이 중복/누락되지
     * 않도록 프론트가 첫 페이지 시각을 고정해 넘긴다. 없으면 현재(KST).
     */
    @Transactional(readOnly = true)
    public PageResponse<PerformanceSummaryResponse> search(String query, Long venueId, int page, int size,
                                                           LocalDateTime asOf) {
        String q = Venue.cleanDisplayText(query);
        if (q != null && q.length() > QUERY_MAX_LENGTH) {
            throw new FieldValidationException("query", "검색어는 100자 이하로 입력해주세요.");
        }
        if (page < 0) {
            throw new FieldValidationException("page", "page는 0 이상이어야 합니다.");
        }
        int pageSize = Math.min(Math.max(size, 1), MAX_PAGE_SIZE);
        LocalDateTime now = asOf != null ? PerformanceSession.truncate(asOf) : sessionTimePolicy.now();

        Page<Performance> result = performanceRepository.search(
                venueId,
                LikePatterns.contains(q),
                now,
                PageRequest.of(page, pageSize));

        Map<Long, PerformanceSessionRepository.SessionStats> stats = result.isEmpty()
                ? Map.of()
                : sessionRepository.findStats(result.map(Performance::getId).getContent(), now).stream()
                        .collect(Collectors.toMap(PerformanceSessionRepository.SessionStats::getPerformanceId,
                                Function.identity()));

        List<PerformanceSummaryResponse> content = result.getContent().stream()
                .map(p -> {
                    PerformanceSessionRepository.SessionStats s = stats.get(p.getId());
                    return new PerformanceSummaryResponse(
                            p.getId(),
                            p.getTitle(),
                            new PerformanceSummaryResponse.VenueRef(p.getVenue().getId(), p.getVenue().getName()),
                            s == null ? null : s.getNextStartsAt(),
                            s == null || s.getSessionCount() == null ? 0 : s.getSessionCount());
                })
                .toList();
        return new PageResponse<>(content, result.getNumber(), result.getSize(),
                result.getTotalElements(), result.getTotalPages());
    }

    @Transactional(readOnly = true)
    public PerformanceDetailResponse get(Long performanceId, Long userId) {
        Performance performance = performanceRepository.findDetailById(performanceId)
                .orElseThrow(() -> new NotFoundException(NOT_FOUND_MESSAGE));
        return toDetail(performance, sessionRepository.findByPerformance_IdOrderByStartsAtAsc(performanceId), userId);
    }

    /**
     * 공연 + 회차 등록. 같은 링크(sourceKey)의 공연이 있으면 409 {"message","performanceId"}.
     * 동시 등록 레이스: insert에서 uk_performance_source_key 위반 → 트랜잭션 롤백 후 새 트랜잭션에서
     * 기존 공연 id를 조회해 같은 409로 응답한다 (메서드 전체를 @Transactional로 묶지 않는 이유).
     */
    public PerformanceDetailResponse create(Long userId, PerformanceCreateRequest request) {
        String sourceUrl = request.sourceUrl() == null ? null : request.sourceUrl().strip();
        String sourceKey = sourceKeyResolver.resolve(sourceUrl);
        // 같은 링크의 공연이 이미 있으면 제목/회차 검증보다 409를 먼저 - 프론트가 기존 공연으로 안내할 수 있게
        Long existingId = transactionTemplate.execute(
                status -> performanceRepository.findIdBySourceKey(sourceKey).orElse(null));
        if (existingId != null) {
            throw duplicatePerformance(existingId);
        }
        String title = cleanTitle(request.title());
        List<LocalDateTime> startsAts = sessionTimePolicy.normalizeAll(request.sessions(), "sessions");

        try {
            return transactionTemplate.execute(status -> {
                // 사전 확인과 insert 사이에 등록된 경우 (unique 제약이 최종 방어)
                performanceRepository.findIdBySourceKey(sourceKey).ifPresent(id -> {
                    throw duplicatePerformance(id);
                });
                Venue venue = findVenue(request.venueId());
                User registrant = requireUser(userId);

                Performance performance = performanceRepository.saveAndFlush(
                        Performance.create(venue, title, sourceUrl, sourceKey, registrant));
                List<PerformanceSession> sessions = new ArrayList<>();
                for (LocalDateTime startsAt : startsAts) {
                    sessions.add(sessionRepository.save(PerformanceSession.create(performance, startsAt)));
                }
                sessionRepository.flush();
                return toDetail(performance, sessions, userId);
            });
        } catch (DataIntegrityViolationException e) {
            if (!DataIntegrityViolations.isViolationOf(e, UNIQUE_SOURCE_KEY)) {
                throw e;
            }
            // 실패한 insert 트랜잭션은 이미 롤백됐다. open-in-view=false라 아래 재조회는 새 트랜잭션/새 세션에서 돈다.
            log.info("Performance create race on sourceKey '{}'", sourceKey);
            Long winnerId = transactionTemplate.execute(
                    status -> performanceRepository.findIdBySourceKey(sourceKey).orElse(null));
            throw duplicatePerformance(winnerId);
        }
    }

    /**
     * 제목/공연장 수정 (등록자만). 공연장 변경은 소속 회차에 티켓이 0건일 때만(아니면 409).
     * 공연 행을 PESSIMISTIC_WRITE로 잠가 "티켓 0건 확인 -> 변경" 사이에 다른 변경이 끼지 않게 한다.
     */
    @Transactional
    public PerformanceDetailResponse update(Long performanceId, Long userId, PerformanceUpdateRequest request) {
        Performance performance = lockPerformance(performanceId);
        requireRegistrant(performance, userId);

        if (request.title() == null && request.venueId() == null) {
            throw new SeatSwapException("수정할 항목이 없습니다.");
        }
        if (request.title() != null) {
            performance.changeTitle(cleanTitle(request.title()));
        }
        if (request.venueId() != null && !request.venueId().equals(performance.getVenue().getId())) {
            Venue venue = findVenue(request.venueId());
            if (ticketRepository.countByPerformanceSession_Performance_Id(performanceId) > 0) {
                throw new ConflictException(VENUE_CHANGE_BLOCKED_MESSAGE);
            }
            performance.changeVenue(venue);
        }
        return toDetail(performance, sessionRepository.findByPerformance_IdOrderByStartsAtAsc(performanceId), userId);
    }

    /**
     * 공연 삭제 (등록자만, 티켓 0건일 때만). 회차를 먼저 지운다.
     * 공연 행을 잠가 회차 추가/수정과 직렬화한다 (회차 추가도 같은 행을 잠근다).
     */
    @Transactional
    public void delete(Long performanceId, Long userId) {
        Performance performance = lockPerformance(performanceId);
        requireRegistrant(performance, userId);
        if (ticketRepository.countByPerformanceSession_Performance_Id(performanceId) > 0) {
            throw new ConflictException(DELETE_BLOCKED_MESSAGE);
        }
        sessionRepository.deleteByPerformanceId(performanceId);
        performanceRepository.deleteById(performanceId);
    }

    // ---- 공용 (PerformanceSessionService에서도 사용) ----

    /**
     * 공연 행 비관적 쓰기 잠금 (SELECT ... FOR UPDATE). 공연에 딸린 데이터를 바꾸는 작업
     * (공연장 변경, 공연 삭제, 회차 추가/수정/삭제)은 모두 이 잠금을 먼저 잡아 서로 직렬화된다.
     * 호출 측 트랜잭션 안에서만 의미가 있다.
     * 주의: 이후 구현할 티켓 등록도 같은 공연 행을 잠가야 "티켓 0건 확인 -> 변경" 검사가 완전해진다.
     */
    Performance lockPerformance(Long performanceId) {
        return performanceRepository.findByIdForUpdate(performanceId)
                .orElseThrow(() -> new NotFoundException(NOT_FOUND_MESSAGE));
    }

    static void requireRegistrant(Performance performance, Long userId) {
        if (!performance.isRegisteredBy(userId)) {
            throw new AccessDeniedException(FORBIDDEN_MESSAGE);
        }
    }

    private PerformanceDetailResponse toDetail(Performance performance, List<PerformanceSession> sessions,
                                               Long userId) {
        User registrant = performance.getRegistrant();
        return new PerformanceDetailResponse(
                performance.getId(),
                performance.getTitle(),
                performance.getSourceUrl(),
                VenueResponse.from(performance.getVenue()),
                new PerformanceDetailResponse.Registrant(registrant.getId(), registrant.getNickname()),
                performance.isRegisteredBy(userId),
                sessions.stream().map(SessionResponse::from).toList(),
                performance.getCreatedAt());
    }

    private Venue findVenue(Long venueId) {
        if (venueId == null) {
            throw new FieldValidationException("venueId", "공연장을 선택해주세요.");
        }
        return venueRepository.findById(venueId)
                .orElseThrow(() -> new FieldValidationException("venueId", VENUE_NOT_FOUND_MESSAGE));
    }

    /** 토큰은 유효하나 사용자가 없는 경우 → 401 (spring-boot-conventions 예외 조항). */
    private User requireUser(Long userId) {
        return userRepository.findById(userId)
                .orElseThrow(() -> new InsufficientAuthenticationException("인증된 사용자를 찾을 수 없습니다."));
    }

    private static String cleanTitle(String title) {
        String cleaned = Venue.cleanDisplayText(title);
        if (cleaned == null || cleaned.isEmpty()) {
            throw new FieldValidationException("title", "공연 제목을 입력해주세요.");
        }
        if (cleaned.length() > Performance.TITLE_MAX_LENGTH) {
            throw new FieldValidationException("title", "공연 제목은 200자 이하로 입력해주세요.");
        }
        return cleaned;
    }

    private static ConflictException duplicatePerformance(Long existingId) {
        return new ConflictException(DUPLICATE_PERFORMANCE_MESSAGE,
                Collections.singletonMap("performanceId", existingId));
    }
}
