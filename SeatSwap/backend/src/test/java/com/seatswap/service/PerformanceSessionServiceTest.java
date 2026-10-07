package com.seatswap.service;

import com.seatswap.domain.Performance;
import com.seatswap.domain.PerformanceSession;
import com.seatswap.domain.User;
import com.seatswap.dto.response.SessionResponse;
import com.seatswap.exception.ConflictException;
import com.seatswap.exception.FieldValidationException;
import com.seatswap.exception.NotFoundException;
import com.seatswap.repository.PerformanceRepository;
import com.seatswap.repository.PerformanceSessionRepository;
import com.seatswap.repository.TicketRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.Optional;

import static com.seatswap.service.PerformanceFixtures.NOW;
import static com.seatswap.service.PerformanceFixtures.performance;
import static com.seatswap.service.PerformanceFixtures.session;
import static com.seatswap.service.PerformanceFixtures.uniqueViolation;
import static com.seatswap.service.PerformanceFixtures.user;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PerformanceSessionServiceTest {

    private PerformanceRepository performanceRepository;
    private PerformanceSessionRepository sessionRepository;
    private TicketRepository ticketRepository;
    private PerformanceSessionService service;

    private final User registrant = user(1L, "등록자");
    private final String kspo = "KSPO DOME";
    private Performance performance;
    private final LocalDateTime future = NOW.plusDays(5).withHour(19);

    @BeforeEach
    void setUp() {
        performanceRepository = mock(PerformanceRepository.class);
        sessionRepository = mock(PerformanceSessionRepository.class);
        ticketRepository = mock(TicketRepository.class);
        service = new PerformanceSessionService(performanceRepository, sessionRepository, ticketRepository,
                PerformanceFixtures.timePolicy(), PerformanceFixtures.noopTransactionManager());
        performance = performance(100L, kspo, registrant);
        when(performanceRepository.findByIdForUpdate(100L)).thenReturn(Optional.of(performance));
    }

    @Test
    void anyUserCanAddSessionTruncatedToMinute() {
        when(sessionRepository.findByPerformance_IdAndStartsAt(100L, future)).thenReturn(Optional.empty());
        when(sessionRepository.saveAndFlush(any(PerformanceSession.class))).thenAnswer(inv -> {
            PerformanceSession s = inv.getArgument(0);
            ReflectionTestUtils.setField(s, "id", 7L);
            return s;
        });

        SessionResponse response = service.add(100L, future.withSecond(42));

        assertThat(response.id()).isEqualTo(7L);
        assertThat(response.startsAt()).isEqualTo(future);
    }

    @Test
    void addDuplicatePastOrMissingPerformance() {
        when(sessionRepository.findByPerformance_IdAndStartsAt(100L, future))
                .thenReturn(Optional.of(session(3L, performance, future)));
        assertThatThrownBy(() -> service.add(100L, future))
                .isInstanceOf(ConflictException.class).hasMessage("이미 등록된 회차입니다.");

        assertThatThrownBy(() -> service.add(100L, NOW.minusDays(1)))
                .isInstanceOfSatisfying(FieldValidationException.class,
                        e -> assertThat(e.getField()).isEqualTo("startsAt"));

        when(performanceRepository.findByIdForUpdate(404L)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.add(404L, future)).isInstanceOf(NotFoundException.class);
    }

    @Test
    void addRaceOnUniqueIs409() {
        when(sessionRepository.findByPerformance_IdAndStartsAt(100L, future)).thenReturn(Optional.empty());
        when(sessionRepository.saveAndFlush(any(PerformanceSession.class)))
                .thenThrow(uniqueViolation("performance_session", "uk_performance_session_performance_starts_at"));

        assertThatThrownBy(() -> service.add(100L, future))
                .isInstanceOf(ConflictException.class).hasMessage("이미 등록된 회차입니다.");
    }

    @Test
    void rescheduleRulesOrder404Then403Then409() {
        PerformanceSession s = session(3L, performance, future);
        when(sessionRepository.findWithPerformanceById(3L)).thenReturn(Optional.of(s));

        // 다른 공연 경로로 접근 → 404
        assertThatThrownBy(() -> service.reschedule(999L, 3L, 1L, future.plusDays(1)))
                .isInstanceOf(NotFoundException.class);
        // 등록자 아님 → 403
        assertThatThrownBy(() -> service.reschedule(100L, 3L, 2L, future.plusDays(1)))
                .isInstanceOf(AccessDeniedException.class);
        // 티켓 있음 → 409
        when(ticketRepository.countByPerformanceSession_Id(3L)).thenReturn(1L);
        assertThatThrownBy(() -> service.reschedule(100L, 3L, 1L, future.plusDays(1)))
                .isInstanceOf(ConflictException.class);
        assertThat(s.getStartsAt()).isEqualTo(future);
    }

    @Test
    void rescheduleByNonRegistrantWithInvalidValueIs403Not400() {
        PerformanceSession s = session(3L, performance, future);
        when(sessionRepository.findWithPerformanceById(3L)).thenReturn(Optional.of(s));

        // 비등록자 + 10분 단위 아님 / 과거 / null → 모두 403
        assertThatThrownBy(() -> service.reschedule(100L, 3L, 2L, future.withMinute(44)))
                .isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> service.reschedule(100L, 3L, 2L, NOW.minusDays(1)))
                .isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> service.reschedule(100L, 3L, 2L, null))
                .isInstanceOf(AccessDeniedException.class);
        // 없는 회차 id + 잘못된 값 → 404
        assertThatThrownBy(() -> service.reschedule(100L, 999L, 1L, future.withMinute(44)))
                .isInstanceOf(NotFoundException.class);
        // 없는 공연 + 잘못된 값 → 404
        when(performanceRepository.findByIdForUpdate(404L)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.add(404L, future.withMinute(44))).isInstanceOf(NotFoundException.class);
        // 등록자 본인이면 400
        assertThatThrownBy(() -> service.reschedule(100L, 3L, 1L, future.withMinute(44)))
                .isInstanceOf(FieldValidationException.class);
    }

    @Test
    void rescheduleToOtherSessionsTimeIs409AndSuccessReturnsSession() {
        PerformanceSession s = session(3L, performance, future);
        when(sessionRepository.findWithPerformanceById(3L)).thenReturn(Optional.of(s));
        when(sessionRepository.findByPerformance_IdAndStartsAt(100L, future.plusDays(1)))
                .thenReturn(Optional.of(session(4L, performance, future.plusDays(1))));

        assertThatThrownBy(() -> service.reschedule(100L, 3L, 1L, future.plusDays(1)))
                .isInstanceOf(ConflictException.class);

        SessionResponse response = service.reschedule(100L, 3L, 1L, future.plusDays(2).withSecond(9));
        assertThat(s.getStartsAt()).isEqualTo(future.plusDays(2));
        assertThat(response.id()).isEqualTo(3L);
        assertThat(response.startsAt()).isEqualTo(future.plusDays(2));
    }

    @Test
    void deleteRequiresRegistrantAndNoTickets() {
        PerformanceSession s = session(3L, performance, future);
        when(sessionRepository.findWithPerformanceById(3L)).thenReturn(Optional.of(s));

        assertThatThrownBy(() -> service.delete(100L, 3L, 2L)).isInstanceOf(AccessDeniedException.class);
        when(ticketRepository.countByPerformanceSession_Id(3L)).thenReturn(1L);
        assertThatThrownBy(() -> service.delete(100L, 3L, 1L)).isInstanceOf(ConflictException.class);
        verify(sessionRepository, never()).delete(any());

        when(ticketRepository.countByPerformanceSession_Id(3L)).thenReturn(0L);
        service.delete(100L, 3L, 1L);
        verify(sessionRepository).delete(s);
    }

    // ---- 리뷰 반영: 공연 삭제와 회차 추가 레이스 ----

    @Test
    void addWhilePerformanceDeletedConcurrentlyIs404NotServerError() {
        // 회차 insert에서 FK 위반(공연이 그 사이 삭제됨) → 새 트랜잭션에서 공연 존재 확인 → 없음 → 404
        DataIntegrityViolationException fk = uniqueViolation("performance_session", "FKabc123");
        when(sessionRepository.findByPerformance_IdAndStartsAt(100L, future)).thenReturn(Optional.empty());
        when(sessionRepository.saveAndFlush(any(PerformanceSession.class))).thenThrow(fk);
        when(performanceRepository.existsById(100L)).thenReturn(false);

        assertThatThrownBy(() -> service.add(100L, future))
                .isInstanceOf(NotFoundException.class).hasMessage("공연을 찾을 수 없습니다.");
    }

    @Test
    void unclassifiedViolationWithPerformanceStillPresentIsRethrownForGeneric409() {
        DataIntegrityViolationException other = uniqueViolation("performance_session", "something_else");
        when(sessionRepository.findByPerformance_IdAndStartsAt(100L, future)).thenReturn(Optional.empty());
        when(sessionRepository.saveAndFlush(any(PerformanceSession.class))).thenThrow(other);
        when(performanceRepository.existsById(100L)).thenReturn(true);

        assertThatThrownBy(() -> service.add(100L, future)).isSameAs(other);
    }

    @Test
    void sessionChangesLockPerformanceRowFirst() {
        when(performanceRepository.findByIdForUpdate(555L)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.delete(555L, 3L, 1L)).isInstanceOf(NotFoundException.class);
        assertThatThrownBy(() -> service.reschedule(555L, 3L, 1L, future)).isInstanceOf(NotFoundException.class);
        verify(sessionRepository, never()).findWithPerformanceById(any());
        verify(performanceRepository, never()).findById(any());
    }

    // ---- 10분 단위 ----

    @Test
    void addAndRescheduleRejectNonTenMinuteStepWith400OnStartsAt() {
        PerformanceSession s = session(3L, performance, future);
        when(sessionRepository.findWithPerformanceById(3L)).thenReturn(Optional.of(s));
        when(sessionRepository.findByPerformance_IdAndStartsAt(any(), any())).thenReturn(Optional.empty());

        for (int minute : new int[]{1, 44}) {
            assertThatThrownBy(() -> service.add(100L, future.withMinute(minute)))
                    .isInstanceOfSatisfying(FieldValidationException.class, e -> {
                        assertThat(e.getField()).isEqualTo("startsAt");
                        assertThat(e.getMessage()).isEqualTo("회차 시각은 10분 단위로 입력해주세요.");
                    });
            assertThatThrownBy(() -> service.reschedule(100L, 3L, 1L, future.withMinute(minute)))
                    .isInstanceOf(FieldValidationException.class);
        }
        assertThat(s.getStartsAt()).isEqualTo(future);
        verify(sessionRepository, never()).saveAndFlush(any());
    }

    @Test
    void addAcceptsZeroTenFiftyAndSecondsAreTruncatedBeforeStepCheck() {
        when(sessionRepository.findByPerformance_IdAndStartsAt(any(), any())).thenReturn(Optional.empty());
        when(sessionRepository.saveAndFlush(any(PerformanceSession.class))).thenAnswer(inv -> inv.getArgument(0));

        for (int minute : new int[]{0, 10, 50}) {
            assertThat(service.add(100L, future.withMinute(minute).withSecond(59)).startsAt())
                    .isEqualTo(future.withMinute(minute));
        }
    }

    @Test
    void existingOffStepSessionCanStillBeDeletedWithoutStepCheck() {
        PerformanceSession legacy = session(9L, performance, future.withMinute(44));
        when(sessionRepository.findWithPerformanceById(9L)).thenReturn(Optional.of(legacy));
        when(ticketRepository.countByPerformanceSession_Id(9L)).thenReturn(0L);

        service.delete(100L, 9L, 1L);

        verify(sessionRepository).delete(legacy);
    }
}
