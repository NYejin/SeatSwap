package com.seatswap.service;

import com.seatswap.domain.Performance;
import com.seatswap.domain.PerformanceSession;
import com.seatswap.domain.User;
import com.seatswap.domain.Venue;
import com.seatswap.dto.request.PerformanceCreateRequest;
import com.seatswap.dto.request.PerformanceUpdateRequest;
import com.seatswap.dto.response.PageResponse;
import com.seatswap.dto.response.PerformanceDetailResponse;
import com.seatswap.dto.response.PerformanceSummaryResponse;
import com.seatswap.exception.ConflictException;
import com.seatswap.exception.FieldValidationException;
import com.seatswap.exception.NotFoundException;
import com.seatswap.repository.PerformanceRepository;
import com.seatswap.repository.PerformanceSessionRepository;
import com.seatswap.repository.TicketRepository;
import com.seatswap.repository.UserRepository;
import com.seatswap.repository.VenueRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

import static com.seatswap.service.PerformanceFixtures.NOW;
import static com.seatswap.service.PerformanceFixtures.performance;
import static com.seatswap.service.PerformanceFixtures.session;
import static com.seatswap.service.PerformanceFixtures.uniqueViolation;
import static com.seatswap.service.PerformanceFixtures.user;
import static com.seatswap.service.PerformanceFixtures.venue;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PerformanceServiceTest {

    private static final String URL = "https://tickets.interpark.com/goods/24013928?utm_source=kakao";
    private static final String KEY = "interpark:24013928";

    private PerformanceRepository performanceRepository;
    private PerformanceSessionRepository sessionRepository;
    private VenueRepository venueRepository;
    private UserRepository userRepository;
    private TicketRepository ticketRepository;
    private PerformanceService service;

    private final User registrant = user(1L, "등록자");
    private final Venue kspo = venue(10L, "KSPO DOME");

    @BeforeEach
    void setUp() {
        performanceRepository = mock(PerformanceRepository.class);
        sessionRepository = mock(PerformanceSessionRepository.class);
        venueRepository = mock(VenueRepository.class);
        userRepository = mock(UserRepository.class);
        ticketRepository = mock(TicketRepository.class);
        service = new PerformanceService(performanceRepository, sessionRepository, venueRepository, userRepository,
                ticketRepository, new SourceKeyResolver(), PerformanceFixtures.timePolicy(),
                PerformanceFixtures.noopTransactionManager());

        when(venueRepository.findById(10L)).thenReturn(Optional.of(kspo));
        when(userRepository.findById(1L)).thenReturn(Optional.of(registrant));
    }

    private void stubInserts() {
        when(performanceRepository.saveAndFlush(any(Performance.class))).thenAnswer(inv -> {
            Performance p = inv.getArgument(0);
            ReflectionTestUtils.setField(p, "id", 100L);
            return p;
        });
        AtomicLong ids = new AtomicLong(1000);
        when(sessionRepository.save(any(PerformanceSession.class))).thenAnswer(inv -> {
            PerformanceSession s = inv.getArgument(0);
            ReflectionTestUtils.setField(s, "id", ids.incrementAndGet());
            return s;
        });
    }

    private static PerformanceCreateRequest request(List<LocalDateTime> sessions) {
        return new PerformanceCreateRequest(URL, "  두아 리파   내한 ", 10L, sessions);
    }

    // ---- create ----

    @Test
    void createStoresNormalizedKeyAndSortedDedupedSessions() {
        stubInserts();
        LocalDateTime d1 = NOW.plusDays(10).withHour(19).withMinute(0);
        PerformanceDetailResponse detail = service.create(1L, request(List.of(
                d1.plusDays(1), d1.withSecond(30), d1, d1.plusDays(1).withSecond(59))));

        assertThat(detail.id()).isEqualTo(100L);
        assertThat(detail.title()).isEqualTo("두아 리파 내한");
        assertThat(detail.sourceUrl()).isEqualTo(URL);
        assertThat(detail.venue().id()).isEqualTo(10L);
        assertThat(detail.registrant().nickname()).isEqualTo("등록자");
        assertThat(detail.canEdit()).isTrue();
        assertThat(detail.sessions()).extracting("startsAt").containsExactly(d1, d1.plusDays(1));
        // 검증 전 사전 확인 + insert 트랜잭션 안 재확인
        verify(performanceRepository, org.mockito.Mockito.times(2)).findIdBySourceKey(KEY);
    }

    @Test
    void createDuplicateLinkIs409WithExistingId() {
        when(performanceRepository.findIdBySourceKey(KEY)).thenReturn(Optional.of(77L));

        assertThatThrownBy(() -> service.create(1L, request(List.of(NOW.plusDays(3)))))
                .isInstanceOfSatisfying(ConflictException.class, e -> {
                    assertThat(e.getMessage()).isEqualTo("이미 등록된 공연입니다.");
                    assertThat(e.getDetails()).containsEntry("performanceId", 77L);
                });
        verify(performanceRepository, never()).saveAndFlush(any());
    }

    @Test
    void createRaceOnSourceKeyIs409WithWinnerId() {
        when(performanceRepository.findIdBySourceKey(KEY))
                .thenReturn(Optional.empty())    // 검증 전 사전 확인
                .thenReturn(Optional.empty())    // insert 트랜잭션 안 확인
                .thenReturn(Optional.of(88L));   // 레이스(unique 위반) 후 새 트랜잭션 재조회
        when(performanceRepository.saveAndFlush(any(Performance.class)))
                .thenThrow(uniqueViolation("performance", "uk_performance_source_key"));

        assertThatThrownBy(() -> service.create(1L, request(List.of(NOW.plusDays(3)))))
                .isInstanceOfSatisfying(ConflictException.class,
                        e -> assertThat(e.getDetails()).containsEntry("performanceId", 88L));
    }

    @Test
    void createRethrowsUnrelatedIntegrityViolation() {
        DataIntegrityViolationException other = uniqueViolation("performance_session", "something_else");
        stubInserts();
        when(sessionRepository.save(any(PerformanceSession.class))).thenThrow(other);

        assertThatThrownBy(() -> service.create(1L, request(List.of(NOW.plusDays(3))))).isSameAs(other);
    }

    @Test
    void createRejectsPastSessionsInvalidLinkTitleAndMissingVenue() {
        assertThatThrownBy(() -> service.create(1L, request(List.of(NOW.plusDays(1), NOW.minusMinutes(1)))))
                .isInstanceOfSatisfying(FieldValidationException.class, e -> {
                    assertThat(e.getField()).isEqualTo("sessions");
                    assertThat(e.getMessage()).isEqualTo("지난 일시는 등록할 수 없습니다.");
                });
        assertThatThrownBy(() -> service.create(1L,
                new PerformanceCreateRequest("ftp://x.com/a", "제목", 10L, List.of(NOW.plusDays(1)))))
                .isInstanceOfSatisfying(FieldValidationException.class,
                        e -> assertThat(e.getField()).isEqualTo("sourceUrl"));
        assertThatThrownBy(() -> service.create(1L,
                new PerformanceCreateRequest(URL, "가".repeat(201), 10L, List.of(NOW.plusDays(1)))))
                .isInstanceOfSatisfying(FieldValidationException.class,
                        e -> assertThat(e.getField()).isEqualTo("title"));
        when(venueRepository.findById(999L)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.create(1L,
                new PerformanceCreateRequest(URL, "제목", 999L, List.of(NOW.plusDays(1)))))
                .isInstanceOfSatisfying(FieldValidationException.class,
                        e -> assertThat(e.getField()).isEqualTo("venueId"));
    }

    @Test
    void createAllowsSessionStartingThisMinute() {
        stubInserts();
        PerformanceDetailResponse detail = service.create(1L, request(List.of(NOW.withSecond(5))));
        assertThat(detail.sessions()).extracting("startsAt").containsExactly(NOW);
    }

    @Test
    void createByDeletedUserIsAuthenticationFailure() {
        when(userRepository.findById(1L)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.create(1L, request(List.of(NOW.plusDays(1)))))
                .isInstanceOf(AuthenticationException.class);
    }

    // ---- lookup / get / search ----

    @Test
    void lookupUsesSourceKey() {
        when(performanceRepository.findIdBySourceKey(KEY)).thenReturn(Optional.of(5L));
        assertThat(service.lookup("http://ticket.interpark.com/Ticket/Goods/GoodsInfo.asp?GoodsCode=24013928"))
                .satisfies(r -> {
                    assertThat(r.exists()).isTrue();
                    assertThat(r.performanceId()).isEqualTo(5L);
                });
        assertThat(service.lookup("https://example.com/x").exists()).isFalse();
    }

    @Test
    void getMarksCanEditOnlyForRegistrant() {
        Performance p = performance(100L, kspo, registrant);
        when(performanceRepository.findDetailById(100L)).thenReturn(Optional.of(p));
        when(sessionRepository.findByPerformance_IdOrderByStartsAtAsc(100L))
                .thenReturn(List.of(session(1L, p, NOW.plusDays(1))));

        assertThat(service.get(100L, 1L).canEdit()).isTrue();
        assertThat(service.get(100L, 2L).canEdit()).isFalse();
        assertThatThrownBy(() -> service.get(404L, 1L)).isInstanceOf(NotFoundException.class);
    }

    @Test
    void searchMapsStatsAndClampsPaging() {
        Performance p1 = performance(100L, kspo, registrant);
        Performance p2 = performance(101L, kspo, registrant);
        when(performanceRepository.search(eq(10L), eq("%두아!_리파%"), eq(NOW), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(p1, p2), PageRequest.of(0, 50), 2));
        PerformanceSessionRepository.SessionStats stats = new PerformanceSessionRepository.SessionStats() {
            public Long getPerformanceId() { return 100L; }
            public Long getSessionCount() { return 3L; }
            public LocalDateTime getNextStartsAt() { return NOW.plusDays(2); }
        };
        when(sessionRepository.findStats(List.of(100L, 101L), NOW)).thenReturn(List.of(stats));

        PageResponse<PerformanceSummaryResponse> page = service.search(" 두아_리파 ", 10L, 0, 500, null);

        assertThat(page.size()).isEqualTo(50);
        assertThat(page.totalElements()).isEqualTo(2);
        assertThat(page.content().get(0).nextSessionStartsAt()).isEqualTo(NOW.plusDays(2));
        assertThat(page.content().get(0).sessionCount()).isEqualTo(3);
        assertThat(page.content().get(0).venue().name()).isEqualTo("KSPO DOME");
        assertThat(page.content().get(1).nextSessionStartsAt()).isNull();
        assertThat(page.content().get(1).sessionCount()).isZero();
        assertThatThrownBy(() -> service.search(null, null, -1, 20, null)).isInstanceOf(FieldValidationException.class);
    }

    // ---- update / delete ----

    @Test
    void updateByNonRegistrantIsForbidden() {
        when(performanceRepository.findByIdForUpdate(100L)).thenReturn(Optional.of(performance(100L, kspo, registrant)));
        assertThatThrownBy(() -> service.update(100L, 2L, new PerformanceUpdateRequest("새 제목", null)))
                .isInstanceOf(AccessDeniedException.class)
                .hasMessage("접근 권한이 없습니다.");
    }

    @Test
    void updateVenueBlockedWhenTicketsExist() {
        Performance p = performance(100L, kspo, registrant);
        when(performanceRepository.findByIdForUpdate(100L)).thenReturn(Optional.of(p));
        when(venueRepository.findById(11L)).thenReturn(Optional.of(venue(11L, "올림픽홀")));
        when(ticketRepository.countByPerformanceSession_Performance_Id(100L)).thenReturn(1L);

        assertThatThrownBy(() -> service.update(100L, 1L, new PerformanceUpdateRequest(null, 11L)))
                .isInstanceOf(ConflictException.class);
        assertThat(p.getVenue().getId()).isEqualTo(10L);
    }

    @Test
    void updateTitleAndVenueWhenNoTickets() {
        Performance p = performance(100L, kspo, registrant);
        when(performanceRepository.findByIdForUpdate(100L)).thenReturn(Optional.of(p));
        when(venueRepository.findById(11L)).thenReturn(Optional.of(venue(11L, "올림픽홀")));
        when(sessionRepository.findByPerformance_IdOrderByStartsAtAsc(100L)).thenReturn(List.of());

        PerformanceDetailResponse detail = service.update(100L, 1L, new PerformanceUpdateRequest(" 새 제목 ", 11L));

        assertThat(detail.title()).isEqualTo("새 제목");
        assertThat(detail.venue().id()).isEqualTo(11L);
    }

    @Test
    void deleteChecksOwnershipAndTicketsThenDeletesSessionsFirst() {
        Performance p = performance(100L, kspo, registrant);
        when(performanceRepository.findByIdForUpdate(100L)).thenReturn(Optional.of(p));

        assertThatThrownBy(() -> service.delete(100L, 2L)).isInstanceOf(AccessDeniedException.class);

        when(ticketRepository.countByPerformanceSession_Performance_Id(100L)).thenReturn(2L);
        assertThatThrownBy(() -> service.delete(100L, 1L)).isInstanceOf(ConflictException.class);
        verify(performanceRepository, never()).deleteById(anyLong());

        when(ticketRepository.countByPerformanceSession_Performance_Id(100L)).thenReturn(0L);
        service.delete(100L, 1L);
        InOrder order = inOrder(sessionRepository, performanceRepository);
        order.verify(sessionRepository).deleteByPerformanceId(100L);
        order.verify(performanceRepository).deleteById(100L);
    }

    // ---- 리뷰 반영 ----

    @Test
    void duplicateLinkIs409EvenWhenTitleAndSessionsAreInvalid() {
        when(performanceRepository.findIdBySourceKey(KEY)).thenReturn(Optional.of(77L));

        assertThatThrownBy(() -> service.create(1L,
                new PerformanceCreateRequest(URL, "가".repeat(300), 10L, List.of(NOW.minusDays(3)))))
                .isInstanceOfSatisfying(ConflictException.class,
                        e -> assertThat(e.getDetails()).containsEntry("performanceId", 77L));
    }

    @Test
    void searchUsesAsOfTruncatedToMinuteInsteadOfNow() {
        LocalDateTime asOf = LocalDateTime.of(2026, 10, 1, 9, 30, 45);
        LocalDateTime truncated = LocalDateTime.of(2026, 10, 1, 9, 30);
        when(performanceRepository.search(eq(null), eq("%"), eq(truncated), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(performance(100L, kspo, registrant)), PageRequest.of(0, 20), 1));

        service.search(null, null, 0, 20, asOf);

        verify(sessionRepository).findStats(List.of(100L), truncated);
    }

    @Test
    void updateAndDeleteLockPerformanceRowAndMissingIs404() {
        when(performanceRepository.findByIdForUpdate(404L)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.update(404L, 1L, new PerformanceUpdateRequest("t", null)))
                .isInstanceOf(NotFoundException.class);
        assertThatThrownBy(() -> service.delete(404L, 1L)).isInstanceOf(NotFoundException.class);
        verify(performanceRepository, never()).findById(anyLong());
    }
}
