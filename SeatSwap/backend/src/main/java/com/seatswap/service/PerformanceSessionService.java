package com.seatswap.service;

import com.seatswap.domain.Performance;
import com.seatswap.domain.PerformanceSession;
import com.seatswap.dto.response.SessionResponse;
import com.seatswap.exception.ConflictException;
import com.seatswap.exception.NotFoundException;
import com.seatswap.repository.PerformanceRepository;
import com.seatswap.repository.PerformanceSessionRepository;
import com.seatswap.repository.TicketRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;

/**
 * 공연 회차 추가·변경·삭제.
 * 추가: 로그인 사용자 누구나 (내 티켓의 회차가 아직 없을 수 있으므로).
 * 변경·삭제: 공연 등록자만 + 그 회차에 티켓이 0건일 때만.
 * 같은 공연에 같은 시각(분 단위) 회차는 409. 동시 요청 레이스는 unique 제약 위반을 409로 변환한다.
 */
@Slf4j
@Service
public class PerformanceSessionService {

    static final String UNIQUE_SESSION = "uk_performance_session_performance_starts_at";
    static final String DUPLICATE_SESSION_MESSAGE = "이미 등록된 회차입니다.";
    static final String SESSION_NOT_FOUND_MESSAGE = "회차를 찾을 수 없습니다.";
    static final String SESSION_LOCKED_MESSAGE = "티켓이 등록된 회차는 수정하거나 삭제할 수 없습니다.";

    private final PerformanceRepository performanceRepository;
    private final PerformanceSessionRepository sessionRepository;
    private final TicketRepository ticketRepository;
    private final SessionTimePolicy sessionTimePolicy;
    private final TransactionTemplate transactionTemplate;

    public PerformanceSessionService(PerformanceRepository performanceRepository,
                                     PerformanceSessionRepository sessionRepository,
                                     TicketRepository ticketRepository,
                                     SessionTimePolicy sessionTimePolicy,
                                     PlatformTransactionManager transactionManager) {
        this.performanceRepository = performanceRepository;
        this.sessionRepository = sessionRepository;
        this.ticketRepository = ticketRepository;
        this.sessionTimePolicy = sessionTimePolicy;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    /**
     * 회차 추가 (로그인 사용자 누구나). 공연 행을 잠가(FOR UPDATE) 공연 삭제와 직렬화한다 —
     * 삭제가 먼저 커밋되면 잠금 대기 후 공연이 없어 404가 된다.
     */
    public SessionResponse add(Long performanceId, LocalDateTime requestedStartsAt) {
        try {
            return transactionTemplate.execute(status -> {
                Performance performance = lockPerformance(performanceId);
                LocalDateTime startsAt = sessionTimePolicy.normalize(requestedStartsAt, "startsAt");
                if (sessionRepository.findByPerformance_IdAndStartsAt(performanceId, startsAt).isPresent()) {
                    throw new ConflictException(DUPLICATE_SESSION_MESSAGE);
                }
                return SessionResponse.from(
                        sessionRepository.saveAndFlush(PerformanceSession.create(performance, startsAt)));
            });
        } catch (DataIntegrityViolationException e) {
            throw translateRace(e, performanceId);
        }
    }

    /**
     * 회차 일시 변경 (등록자만, 티켓 0건일 때만). 응답은 {id, startsAt}.
     * 조회·권한 확인(404/403/409) 뒤에 입력 검증(400)을 한다 - 비등록자에게 입력 규칙을 알려주지 않기 위해.
     */
    public SessionResponse reschedule(Long performanceId, Long sessionId, Long userId,
                                      LocalDateTime requestedStartsAt) {
        try {
            return transactionTemplate.execute(status -> {
                PerformanceSession session = loadEditableSession(performanceId, sessionId, userId);
                LocalDateTime startsAt = sessionTimePolicy.normalize(requestedStartsAt, "startsAt");
                if (!startsAt.equals(session.getStartsAt())) {
                    sessionRepository.findByPerformance_IdAndStartsAt(performanceId, startsAt).ifPresent(other -> {
                        throw new ConflictException(DUPLICATE_SESSION_MESSAGE);
                    });
                    session.reschedule(startsAt);
                    sessionRepository.flush();
                }
                return SessionResponse.from(session);
            });
        } catch (DataIntegrityViolationException e) {
            throw translateRace(e, performanceId);
        }
    }

    @Transactional
    public void delete(Long performanceId, Long sessionId, Long userId) {
        PerformanceSession session = loadEditableSession(performanceId, sessionId, userId);
        sessionRepository.delete(session);
    }

    /**
     * 공연 행을 먼저 잠근 뒤(404) 회차를 확인한다: 404(회차 없음/다른 공연 소속) → 403(등록자 아님) → 409(티켓 있음).
     * 잠금으로 같은 공연의 회차 변경·공연 삭제가 직렬화된다.
     */
    private PerformanceSession loadEditableSession(Long performanceId, Long sessionId, Long userId) {
        Performance performance = lockPerformance(performanceId);
        PerformanceSession session = sessionRepository.findWithPerformanceById(sessionId)
                .filter(s -> s.getPerformance().getId().equals(performanceId))
                .orElseThrow(() -> new NotFoundException(SESSION_NOT_FOUND_MESSAGE));
        PerformanceService.requireRegistrant(performance, userId);
        if (ticketRepository.countByPerformanceSession_Id(sessionId) > 0) {
            throw new ConflictException(SESSION_LOCKED_MESSAGE);
        }
        return session;
    }

    private Performance lockPerformance(Long performanceId) {
        return performanceRepository.findByIdForUpdate(performanceId)
                .orElseThrow(() -> new NotFoundException(PerformanceService.NOT_FOUND_MESSAGE));
    }

    /**
     * 레이스로 난 무결성 위반 분류.
     * - 회차 unique 위반 → 409 "이미 등록된 회차입니다."
     * - 그 사이 공연이 삭제됨(FK 위반 등) → 새 트랜잭션에서 공연 존재 확인 후 404
     * - 그 외 → 그대로 전파 (GlobalExceptionHandler가 409 일반 문구로 응답)
     */
    private RuntimeException translateRace(DataIntegrityViolationException e, Long performanceId) {
        if (DataIntegrityViolations.isViolationOf(e, UNIQUE_SESSION)) {
            log.info("Performance session race on unique (performance_id, starts_at)");
            return new ConflictException(DUPLICATE_SESSION_MESSAGE);
        }
        // 실패한 트랜잭션은 이미 롤백됐다. open-in-view=false라 이 조회는 새 트랜잭션/새 세션에서 돈다.
        Boolean exists = transactionTemplate.execute(status -> performanceRepository.existsById(performanceId));
        if (!Boolean.TRUE.equals(exists)) {
            log.info("Performance {} was deleted concurrently", performanceId);
            return new NotFoundException(PerformanceService.NOT_FOUND_MESSAGE);
        }
        return e;
    }
}
