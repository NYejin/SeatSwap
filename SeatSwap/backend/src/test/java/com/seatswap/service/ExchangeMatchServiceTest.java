package com.seatswap.service;

import com.seatswap.domain.ExchangeMatch;
import com.seatswap.domain.ExchangeMatchAction;
import com.seatswap.domain.ExchangeMatchStatus;
import com.seatswap.domain.ExchangeRequest;
import com.seatswap.domain.ExchangeRequestStatus;
import com.seatswap.domain.ExtraType;
import com.seatswap.domain.Performance;
import com.seatswap.domain.PerformanceSession;
import com.seatswap.domain.Ticket;
import com.seatswap.domain.TicketStatus;
import com.seatswap.domain.User;
import com.seatswap.dto.response.ExchangeMatchResponse;
import com.seatswap.exception.BusinessRuleException;
import com.seatswap.exception.ConflictException;
import com.seatswap.exception.ForbiddenException;
import com.seatswap.exception.NotFoundException;
import com.seatswap.repository.ExchangeCandidateRepository;
import com.seatswap.repository.ExchangeMatchRepository;
import com.seatswap.repository.ExchangeRequestRepository;
import com.seatswap.repository.ExchangeTicketLockRepository;
import com.seatswap.repository.TicketRepository;
import com.seatswap.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static com.seatswap.service.PerformanceFixtures.NOW;
import static com.seatswap.service.PerformanceFixtures.performance;
import static com.seatswap.service.PerformanceFixtures.session;
import static com.seatswap.service.PerformanceFixtures.user;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 기준 시각 2026-10-06 12:00 KST. 내(1번 사용자) 티켓 700 / 요청 900, 상대(2번) 티켓 600 / 요청 800 -
 * 일부러 내 id 가 더 커서 '티켓·요청 id 오름차순 잠금'을 검증할 수 있다. SQL 판정과 실제 경쟁은 실제 MySQL 테스트가 검증한다.
 */
class ExchangeMatchServiceTest {

    private static final long MY_REQ = 900L;
    private static final long THEIR_REQ = 800L;
    private static final long MY_TICKET = 700L;
    private static final long THEIR_TICKET = 600L;
    private static final long MATCH = 50L;

    private ExchangeMatchRepository matchRepository;
    private ExchangeRequestRepository requestRepository;
    private TicketRepository ticketRepository;
    private ExchangeTicketLockRepository lockRepository;
    private ExchangeCandidateRepository candidateRepository;
    private UserRepository userRepository;
    private ExchangeMatchService service;

    private final User me = user(1L, "나");
    private final User other = user(2L, "상대");
    private final Performance performance = performance(10L, "KSPO DOME", other);
    private final PerformanceSession openSession = session(7L, performance, LocalDateTime.of(2026, 11, 1, 19, 0));
    private final PerformanceSession closedSession = session(8L, performance, LocalDateTime.of(2026, 10, 5, 19, 0));

    private Ticket myTicket;
    private Ticket theirTicket;
    private ExchangeRequest myRequest;
    private ExchangeRequest theirRequest;

    @BeforeEach
    void setUp() {
        matchRepository = mock(ExchangeMatchRepository.class);
        requestRepository = mock(ExchangeRequestRepository.class);
        ticketRepository = mock(TicketRepository.class);
        lockRepository = mock(ExchangeTicketLockRepository.class);
        candidateRepository = mock(ExchangeCandidateRepository.class);
        userRepository = mock(UserRepository.class);
        service = new ExchangeMatchService(matchRepository, requestRepository, ticketRepository, lockRepository,
                candidateRepository, userRepository, PerformanceFixtures.timePolicy(),
                PerformanceFixtures.noopTransactionManager());

        myTicket = ticket(MY_TICKET, me, openSession);
        theirTicket = ticket(THEIR_TICKET, other, openSession);
        myRequest = request(MY_REQ, myTicket);
        theirRequest = request(THEIR_REQ, theirTicket);

        when(requestRepository.findTicketIdById(MY_REQ)).thenReturn(Optional.of(MY_TICKET));
        when(requestRepository.findTicketIdById(THEIR_REQ)).thenReturn(Optional.of(THEIR_TICKET));
        when(ticketRepository.findOwnerIdById(MY_TICKET)).thenReturn(Optional.of(1L));
        when(ticketRepository.findByIdForUpdate(MY_TICKET)).thenReturn(Optional.of(myTicket));
        when(ticketRepository.findByIdForUpdate(THEIR_TICKET)).thenReturn(Optional.of(theirTicket));
        when(ticketRepository.findWithSessionById(MY_TICKET)).thenReturn(Optional.of(myTicket));
        when(requestRepository.findByIdForUpdate(MY_REQ)).thenReturn(Optional.of(myRequest));
        when(requestRepository.findByIdForUpdate(THEIR_REQ)).thenReturn(Optional.of(theirRequest));
        when(candidateRepository.isCandidatePair(eq(MY_REQ), eq(THEIR_REQ), any())).thenReturn(true);
        when(matchRepository.findOpenByPair(anyLong(), anyLong())).thenReturn(List.of());
        when(matchRepository.saveAndFlush(any(ExchangeMatch.class))).thenAnswer(inv -> {
            ExchangeMatch m = inv.getArgument(0);
            if (m.getId() == null) {
                ReflectionTestUtils.setField(m, "id", MATCH);
            }
            return m;
        });
        when(userRepository.findById(1L)).thenReturn(Optional.of(me));
        when(userRepository.findById(2L)).thenReturn(Optional.of(other));
    }

    private static Ticket ticket(long id, User owner, PerformanceSession session) {
        Ticket t = Ticket.create(owner, session, "A", "A", "3", "3", "5", "5");
        ReflectionTestUtils.setField(t, "id", id);
        return t;
    }

    private static ExchangeRequest request(long id, Ticket ticket) {
        ExchangeRequest r = ExchangeRequest.create(ticket, ExtraType.X, null);
        ReflectionTestUtils.setField(r, "id", id);
        return r;
    }

    /** 내가 a(제안자), 상대가 b 인 매칭을 상태와 함께 mock 저장소에 등록한다. */
    private ExchangeMatch existingMatch(ExchangeMatchStatus status) {
        ExchangeMatch m = ExchangeMatch.propose(MY_REQ, THEIR_REQ, MY_TICKET, THEIR_TICKET, 1L, 2L);
        ReflectionTestUtils.setField(m, "id", MATCH);
        ReflectionTestUtils.setField(m, "status", status);
        if (status == ExchangeMatchStatus.RESERVED || status == ExchangeMatchStatus.COMPLETED) {
            ReflectionTestUtils.setField(m, "aReservedAt", NOW.minusHours(2));
            ReflectionTestUtils.setField(m, "bReservedAt", NOW.minusHours(1));
        }
        if (status == ExchangeMatchStatus.CANCELED) {
            ReflectionTestUtils.setField(m, "canceledAt", NOW.minusHours(1));
        }
        when(matchRepository.findById(MATCH)).thenReturn(Optional.of(m));
        when(matchRepository.findByIdForUpdate(MATCH)).thenReturn(Optional.of(m));
        return m;
    }

    // ------------------------------------------------------------------ 제안

    @Test
    void 제안하면_CHATTING_매칭을_만들고_티켓_요청을_id_오름차순으로_잠근다() {
        ExchangeMatchResponse response = service.propose(1L, MY_REQ, THEIR_REQ);

        assertThat(response.id()).isEqualTo(MATCH);
        assertThat(response.status()).isEqualTo("CHATTING");
        assertThat(response.mySide()).isEqualTo("A");
        assertThat(response.myRequestId()).isEqualTo(MY_REQ);
        assertThat(response.counterpartRequestId()).isEqualTo(THEIR_REQ);
        assertThat(response.counterpartTicketId()).isEqualTo(THEIR_TICKET);
        assertThat(response.counterpartNickname()).isEqualTo("상대");
        assertThat(response.myReservedAt()).isNull();
        assertThat(response.canceledBy()).isNull();

        InOrder order = inOrder(ticketRepository, requestRepository, matchRepository);
        order.verify(ticketRepository).findByIdForUpdate(THEIR_TICKET);   // 600 -> 700
        order.verify(ticketRepository).findByIdForUpdate(MY_TICKET);
        order.verify(requestRepository).findByIdForUpdate(THEIR_REQ);      // 800 -> 900
        order.verify(requestRepository).findByIdForUpdate(MY_REQ);
        order.verify(matchRepository).findOpenByPair(MY_REQ, THEIR_REQ);
        order.verify(matchRepository).saveAndFlush(any(ExchangeMatch.class));
        verify(lockRepository, never()).insert(anyLong(), anyLong(), any());
    }

    @Test
    void 제안한_매칭은_제안자가_a측이고_티켓_사용자가_채워진다() {
        service.propose(1L, MY_REQ, THEIR_REQ);

        org.mockito.ArgumentCaptor<ExchangeMatch> captor = org.mockito.ArgumentCaptor.forClass(ExchangeMatch.class);
        verify(matchRepository).saveAndFlush(captor.capture());
        ExchangeMatch saved = captor.getValue();
        assertThat(saved.getRequestAId()).isEqualTo(MY_REQ);
        assertThat(saved.getRequestBId()).isEqualTo(THEIR_REQ);
        assertThat(saved.getTicketAId()).isEqualTo(MY_TICKET);
        assertThat(saved.getTicketBId()).isEqualTo(THEIR_TICKET);
        assertThat(saved.getUserAId()).isEqualTo(1L);
        assertThat(saved.getUserBId()).isEqualTo(2L);
    }

    @Test
    void 남의_요청으로_제안하면_403이고_잠금을_잡지_않는다() {
        when(ticketRepository.findOwnerIdById(MY_TICKET)).thenReturn(Optional.of(99L));

        assertThatThrownBy(() -> service.propose(1L, MY_REQ, THEIR_REQ)).isInstanceOf(ForbiddenException.class);

        verify(ticketRepository, never()).findByIdForUpdate(anyLong());
    }

    @Test
    void 없는_요청은_404() {
        when(requestRepository.findTicketIdById(MY_REQ)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.propose(1L, MY_REQ, THEIR_REQ)).isInstanceOf(NotFoundException.class);

        when(requestRepository.findTicketIdById(MY_REQ)).thenReturn(Optional.of(MY_TICKET));
        when(requestRepository.findTicketIdById(THEIR_REQ)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.propose(1L, MY_REQ, THEIR_REQ)).isInstanceOf(NotFoundException.class);
    }

    @Test
    void 잠금_사이에_요청이_삭제됐으면_404() {
        when(requestRepository.findByIdForUpdate(THEIR_REQ)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.propose(1L, MY_REQ, THEIR_REQ)).isInstanceOf(NotFoundException.class);
        verify(matchRepository, never()).saveAndFlush(any());
    }

    @Test
    void 자기_자신의_요청을_고르면_422() {
        assertThatThrownBy(() -> service.propose(1L, MY_REQ, MY_REQ))
                .isInstanceOfSatisfying(BusinessRuleException.class, e -> assertThat(e.getCode()).isEqualTo("NOT_A_CANDIDATE"));
    }

    @Test
    void 같은_쌍의_열린_매칭이_있으면_409이고_matchId를_알려준다() {
        ExchangeMatch open = existingMatch(ExchangeMatchStatus.CHATTING);
        when(matchRepository.findOpenByPair(MY_REQ, THEIR_REQ)).thenReturn(List.of(open));

        assertThatThrownBy(() -> service.propose(1L, MY_REQ, THEIR_REQ))
                .isInstanceOfSatisfying(ConflictException.class, e -> {
                    assertThat(e.getDetails()).containsEntry("code", "MATCH_ALREADY_OPEN").containsEntry("matchId", MATCH);
                });
        verify(matchRepository, never()).saveAndFlush(any());
    }

    @Test
    void 취소된_매칭만_있으면_다시_제안할_수_있다() {
        // 열린 매칭 조회는 CHATTING/RESERVED 만 보므로 취소·완료 이력이 있어도 빈 목록이다 (재매칭 불가는 차단·신고뿐)
        when(matchRepository.findOpenByPair(MY_REQ, THEIR_REQ)).thenReturn(List.of());

        assertThat(service.propose(1L, MY_REQ, THEIR_REQ).status()).isEqualTo("CHATTING");
    }

    @Test
    void 제안_재검증_실패_사례는_모두_422이고_매칭을_만들지_않는다() {
        // 1) 후보 조건 불충족 (SQL 판정)
        when(candidateRepository.isCandidatePair(eq(MY_REQ), eq(THEIR_REQ), any())).thenReturn(false);
        assertCode(NOT_A_CANDIDATE());

        // 2) 상대 요청 닫힘
        when(candidateRepository.isCandidatePair(eq(MY_REQ), eq(THEIR_REQ), any())).thenReturn(true);
        ReflectionTestUtils.setField(theirRequest, "status", ExchangeRequestStatus.CLOSED);
        assertCode(NOT_A_CANDIDATE());
        ReflectionTestUtils.setField(theirRequest, "status", ExchangeRequestStatus.OPEN);

        // 3) 상대 티켓 INACTIVE
        ReflectionTestUtils.setField(theirTicket, "status", TicketStatus.INACTIVE);
        assertCode(NOT_A_CANDIDATE());
        ReflectionTestUtils.setField(theirTicket, "status", TicketStatus.ACTIVE);

        // 4) 내 요청 닫힘 / 내 티켓 INACTIVE
        ReflectionTestUtils.setField(myRequest, "status", ExchangeRequestStatus.CLOSED);
        assertCode("TICKET_NOT_ACTIVE");
        ReflectionTestUtils.setField(myRequest, "status", ExchangeRequestStatus.OPEN);
        ReflectionTestUtils.setField(myTicket, "status", TicketStatus.INACTIVE);
        assertCode("TICKET_NOT_ACTIVE");
        ReflectionTestUtils.setField(myTicket, "status", TicketStatus.ACTIVE);

        // 5) 내 티켓이 예약 잠금 -> TICKET_LOCKED (후보 판정을 통과한 뒤에만 구분)
        when(lockRepository.existsAnyByTicketIds(List.of(MY_TICKET))).thenReturn(true);
        assertCode("TICKET_LOCKED");
        when(lockRepository.existsAnyByTicketIds(List.of(MY_TICKET))).thenReturn(false);

        verify(matchRepository, never()).saveAndFlush(any());
    }

    @Test
    void 내_회차_마감이_지났으면_422_SESSION_CLOSED() {
        Ticket closedTicket = ticket(MY_TICKET, me, closedSession);
        when(ticketRepository.findWithSessionById(MY_TICKET)).thenReturn(Optional.of(closedTicket));

        assertCode("SESSION_CLOSED");
    }

    @Test
    void 상대_티켓이_잠겼으면_NOT_A_CANDIDATE로_합쳐_예약_상태를_노출하지_않는다() {
        when(lockRepository.existsAnyByTicketIds(List.of(THEIR_TICKET))).thenReturn(true);

        assertCode("NOT_A_CANDIDATE");
        verify(matchRepository, never()).saveAndFlush(any());
    }

    @Test
    void 후보가_아니면_잠금_상태와_무관하게_NOT_A_CANDIDATE이고_잠금을_조회하지_않는다() {
        when(candidateRepository.isCandidatePair(eq(MY_REQ), eq(THEIR_REQ), any())).thenReturn(false);
        when(lockRepository.existsAnyByTicketIds(any())).thenReturn(true);

        assertCode("NOT_A_CANDIDATE");
        verify(lockRepository, never()).existsAnyByTicketIds(any());
    }

    private static String NOT_A_CANDIDATE() {
        return "NOT_A_CANDIDATE";
    }

    private void assertCode(String code) {
        assertThatThrownBy(() -> service.propose(1L, MY_REQ, THEIR_REQ))
                .isInstanceOfSatisfying(BusinessRuleException.class, e -> assertThat(e.getCode()).isEqualTo(code));
    }

    // ------------------------------------------------------------------ 수락(예약 동의)

    @Test
    void 한쪽만_수락하면_CHATTING을_유지하고_누른_시각만_기록하며_잠금은_없다() {
        ExchangeMatch m = existingMatch(ExchangeMatchStatus.CHATTING);

        ExchangeMatchResponse response = service.accept(1L, MATCH);

        assertThat(m.getStatus()).isEqualTo(ExchangeMatchStatus.CHATTING);
        assertThat(m.getAReservedAt()).isEqualTo(NOW);
        assertThat(m.getBReservedAt()).isNull();
        assertThat(response.status()).isEqualTo("CHATTING");
        assertThat(response.myReservedAt()).isEqualTo(NOW);
        assertThat(response.counterpartReservedAt()).isNull();
        verify(lockRepository, never()).insert(anyLong(), anyLong(), any());
    }

    @Test
    void 양쪽이_수락하면_RESERVED가_되고_두_티켓_잠금을_id_오름차순으로_넣는다() {
        ExchangeMatch m = existingMatch(ExchangeMatchStatus.CHATTING);
        ReflectionTestUtils.setField(m, "aReservedAt", NOW.minusMinutes(3));

        ExchangeMatchResponse response = service.accept(2L, MATCH);   // b 가 두 번째로 누른다

        assertThat(m.getStatus()).isEqualTo(ExchangeMatchStatus.RESERVED);
        assertThat(response.status()).isEqualTo("RESERVED");
        assertThat(response.mySide()).isEqualTo("B");
        InOrder order = inOrder(ticketRepository, requestRepository, matchRepository, lockRepository);
        order.verify(ticketRepository).findByIdForUpdate(THEIR_TICKET);
        order.verify(ticketRepository).findByIdForUpdate(MY_TICKET);
        order.verify(requestRepository).findByIdForUpdate(THEIR_REQ);
        order.verify(requestRepository).findByIdForUpdate(MY_REQ);
        order.verify(matchRepository).findByIdForUpdate(MATCH);
        order.verify(lockRepository).insert(eq(THEIR_TICKET), eq(MATCH), any());
        order.verify(lockRepository).insert(eq(MY_TICKET), eq(MATCH), any());
    }

    @Test
    void 이미_누른_쪽이_다시_수락하면_멱등으로_200이고_아무것도_바꾸지_않는다() {
        ExchangeMatch m = existingMatch(ExchangeMatchStatus.CHATTING);
        ReflectionTestUtils.setField(m, "aReservedAt", NOW.minusMinutes(3));

        ExchangeMatchResponse response = service.accept(1L, MATCH);

        assertThat(response.myReservedAt()).isEqualTo(NOW.minusMinutes(3));
        assertThat(m.getAReservedAt()).isEqualTo(NOW.minusMinutes(3));
        verify(lockRepository, never()).insert(anyLong(), anyLong(), any());
        verify(lockRepository, never()).existsAnyByTicketIds(any());
    }

    @Test
    void RESERVED에서_다시_수락해도_멱등_200이고_잠금을_다시_넣지_않는다() {
        existingMatch(ExchangeMatchStatus.RESERVED);

        assertThat(service.accept(2L, MATCH).status()).isEqualTo("RESERVED");
        verify(lockRepository, never()).insert(anyLong(), anyLong(), any());
    }

    @Test
    void 티켓이_다른_매칭에서_이미_잠겼으면_409이고_누름도_기록하지_않는다() {
        ExchangeMatch m = existingMatch(ExchangeMatchStatus.CHATTING);
        ReflectionTestUtils.setField(m, "aReservedAt", NOW.minusMinutes(3));
        when(lockRepository.existsAnyByTicketIds(anyCollection())).thenReturn(true);

        assertThatThrownBy(() -> service.accept(2L, MATCH))
                .isInstanceOfSatisfying(ConflictException.class,
                        e -> assertThat(e.getDetails()).containsEntry("code", "TICKET_ALREADY_RESERVED"));

        assertThat(m.getStatus()).isEqualTo(ExchangeMatchStatus.CHATTING);
        assertThat(m.getBReservedAt()).isNull();
        verify(lockRepository, never()).insert(anyLong(), anyLong(), any());
    }

    @Test
    void 잠금_INSERT가_PK_충돌이면_409로_바꾼다() {
        ExchangeMatch m = existingMatch(ExchangeMatchStatus.CHATTING);
        ReflectionTestUtils.setField(m, "aReservedAt", NOW.minusMinutes(3));
        doThrow(new DuplicateKeyException("dup")).when(lockRepository).insert(eq(MY_TICKET), eq(MATCH), any());

        assertThatThrownBy(() -> service.accept(2L, MATCH))
                .isInstanceOfSatisfying(ConflictException.class,
                        e -> assertThat(e.getDetails()).containsEntry("code", "TICKET_ALREADY_RESERVED"));
    }

    @Test
    void 매칭_비참여자는_없는_매칭과_같은_404() {
        existingMatch(ExchangeMatchStatus.CHATTING);

        for (ExchangeMatchAction action : List.of(ExchangeMatchAction.ACCEPT, ExchangeMatchAction.REJECT, ExchangeMatchAction.CANCEL)) {
            assertThatThrownBy(() -> run(action, 3L, MATCH)).isInstanceOf(NotFoundException.class)
                    .hasMessage(ExchangeMatchService.MATCH_NOT_FOUND_MESSAGE);
            assertThatThrownBy(() -> run(action, 1L, 999L)).isInstanceOf(NotFoundException.class);
        }
        verify(ticketRepository, never()).findByIdForUpdate(anyLong());
    }

    // ------------------------------------------------------------------ 거절·취소

    @Test
    void 제안받은_쪽은_거절할_수_있고_CANCELED로_바뀌며_잠금_해제를_호출한다() {
        ExchangeMatch m = existingMatch(ExchangeMatchStatus.CHATTING);

        ExchangeMatchResponse response = service.reject(2L, MATCH);

        assertThat(m.getStatus()).isEqualTo(ExchangeMatchStatus.CANCELED);
        assertThat(m.getCanceledById()).isEqualTo(2L);
        assertThat(m.getCanceledAt()).isEqualTo(NOW);
        assertThat(response.canceledBy()).isEqualTo("ME");
        verify(lockRepository).deleteByMatchId(MATCH);
    }

    @Test
    void 제안한_쪽이_거절을_부르면_403이다_취소를_써야_한다() {
        ExchangeMatch m = existingMatch(ExchangeMatchStatus.CHATTING);

        assertThatThrownBy(() -> service.reject(1L, MATCH))
                .isInstanceOfSatisfying(ForbiddenException.class,
                        e -> assertThat(e.getMessage()).isEqualTo(ExchangeMatchService.FORBIDDEN_REJECT_MESSAGE));
        assertThat(m.getStatus()).isEqualTo(ExchangeMatchStatus.CHATTING);
    }

    @Test
    void 취소는_양쪽_누구나_할_수_있고_상대_화면에서는_COUNTERPART다() {
        ExchangeMatch m = existingMatch(ExchangeMatchStatus.CHATTING);

        ExchangeMatchResponse mine = service.cancel(1L, MATCH);

        assertThat(m.getStatus()).isEqualTo(ExchangeMatchStatus.CANCELED);
        assertThat(m.getCanceledById()).isEqualTo(1L);
        assertThat(mine.canceledBy()).isEqualTo("ME");
        // 상대(b)가 같은 매칭을 조회한다면 COUNTERPART 로 보인다
        assertThat(service.toResponse(m, 2L).canceledBy()).isEqualTo("COUNTERPART");
    }

    @Test
    void RESERVED에서_취소하면_잠금을_해제한다() {
        ExchangeMatch m = existingMatch(ExchangeMatchStatus.RESERVED);

        service.cancel(2L, MATCH);

        assertThat(m.getStatus()).isEqualTo(ExchangeMatchStatus.CANCELED);
        verify(lockRepository).deleteByMatchId(MATCH);
    }

    @Test
    void 시스템_취소된_매칭은_canceledBy가_SYSTEM이다() {
        ExchangeMatch m = existingMatch(ExchangeMatchStatus.CANCELED);

        assertThat(service.toResponse(m, 1L).canceledBy()).isEqualTo("SYSTEM");
    }

    // ------------------------------------------------------------------ 전이 표 (서비스 동작 x 상태 전 조합)

    private ExchangeMatchResponse run(ExchangeMatchAction action, long userId, long matchId) {
        return switch (action) {
            case ACCEPT -> service.accept(userId, matchId);
            case REJECT -> service.reject(userId, matchId);
            case CANCEL -> service.cancel(userId, matchId);
            default -> throw new IllegalArgumentException(action.name());
        };
    }

    @Test
    void 전이_표_상태4종_x_동작3종_서비스_경로_전체() {
        for (ExchangeMatchStatus status : ExchangeMatchStatus.values()) {
            for (ExchangeMatchAction action : List.of(ExchangeMatchAction.ACCEPT, ExchangeMatchAction.REJECT, ExchangeMatchAction.CANCEL)) {
                existingMatch(status);
                long actor = action == ExchangeMatchAction.REJECT ? 2L : 1L;   // reject 는 b측만
                boolean allowed = ExchangeMatch.isAllowed(status, action);
                if (allowed) {
                    ExchangeMatchResponse response = run(action, actor, MATCH);
                    if (action == ExchangeMatchAction.ACCEPT) {
                        // CHATTING 은 한쪽 수락(CHATTING 유지), RESERVED 는 멱등
                        assertThat(response.status()).as(status + " + " + action).isEqualTo(status.name());
                    } else {
                        assertThat(response.status()).as(status + " + " + action).isEqualTo("CANCELED");
                    }
                } else {
                    assertThatThrownBy(() -> run(action, actor, MATCH)).as(status + " + " + action)
                            .isInstanceOfSatisfying(ConflictException.class, e -> {
                                assertThat(e.getDetails()).containsEntry("code", "MATCH_STATE_CONFLICT")
                                        .containsEntry("status", status.name()).containsEntry("action", action.name());
                                assertThat(e.getMessage()).isNotBlank();
                            });
                }
            }
        }
    }

    @Test
    void 취소된_매칭과_완료된_매칭의_불허_메시지는_한국어로_구분된다() {
        existingMatch(ExchangeMatchStatus.CANCELED);
        assertThatThrownBy(() -> service.accept(1L, MATCH)).hasMessage("이미 취소된 매칭입니다.");
        existingMatch(ExchangeMatchStatus.COMPLETED);
        assertThatThrownBy(() -> service.cancel(1L, MATCH)).hasMessage("이미 교환이 완료된 매칭입니다.");
    }
}
