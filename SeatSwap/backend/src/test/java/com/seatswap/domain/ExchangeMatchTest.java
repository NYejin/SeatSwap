package com.seatswap.domain;

import com.seatswap.domain.ExchangeMatch.Side;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ExchangeMatchTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 10, 8, 12, 0);

    private static ExchangeMatch match() {
        return ExchangeMatch.propose(900L, 800L, 700L, 600L, 1L, 2L,
                new com.seatswap.domain.WantExtra(com.seatswap.domain.ExtraType.X, null),
                new com.seatswap.domain.WantExtra(com.seatswap.domain.ExtraType.POS, 3000));
    }

    /** 상태 4종 x 동작 6종 = 24 조합 전부. 서비스 Javadoc의 전이 표와 같아야 한다(멱등 200 은 서비스가 먼저 걸러낸다). */
    @ParameterizedTest(name = "{0} + {1} -> {2}")
    @CsvSource({
            "CHATTING,PROPOSE,false", "CHATTING,RESERVE,true", "CHATTING,UNRESERVE,true", "CHATTING,REJECT,true", "CHATTING,CANCEL,true", "CHATTING,COMPLETE,false",
            "RESERVED,PROPOSE,false", "RESERVED,RESERVE,true", "RESERVED,UNRESERVE,true", "RESERVED,REJECT,false", "RESERVED,CANCEL,false", "RESERVED,COMPLETE,true",
            "COMPLETED,PROPOSE,true", "COMPLETED,RESERVE,false", "COMPLETED,UNRESERVE,false", "COMPLETED,REJECT,false", "COMPLETED,CANCEL,false", "COMPLETED,COMPLETE,false",
            "CANCELED,PROPOSE,true", "CANCELED,RESERVE,false", "CANCELED,UNRESERVE,false", "CANCELED,REJECT,false", "CANCELED,CANCEL,false", "CANCELED,COMPLETE,false"
    })
    void transitionTable(ExchangeMatchStatus status, ExchangeMatchAction action, boolean allowed) {
        assertThat(ExchangeMatch.isAllowed(status, action)).isEqualTo(allowed);
    }

    @Test
    void proposeStartsChattingWithNothingReservedOrCanceled() {
        ExchangeMatch m = match();

        assertThat(m.getStatus()).isEqualTo(ExchangeMatchStatus.CHATTING);
        assertThat(m.getReservedById()).isNull();
        assertThat(m.getReservedAt()).isNull();
        assertThat(m.getCanceledAt()).isNull();
        assertThat(m.isOpen()).isTrue();
    }

    @Test
    void sideOfDistinguishesProposerAndTarget() {
        ExchangeMatch m = match();

        assertThat(m.sideOf(1L)).isEqualTo(Side.A);
        assertThat(m.sideOf(2L)).isEqualTo(Side.B);
        assertThat(m.sideOf(3L)).isNull();
        assertThat(m.sideOf(null)).isNull();
    }

    @Test
    void oneSideReservingMakesItReservedImmediately() {
        ExchangeMatch m = match();

        m.reserve(2L, NOW);

        assertThat(m.getStatus()).isEqualTo(ExchangeMatchStatus.RESERVED);
        assertThat(m.getReservedById()).isEqualTo(2L);
        assertThat(m.getReservedAt()).isEqualTo(NOW);
        assertThat(m.isOpen()).isTrue();
    }

    @Test
    void reserveRequiresChattingAndParticipant() {
        ExchangeMatch m = match();
        assertThatThrownBy(() -> m.reserve(3L, NOW)).isInstanceOf(IllegalArgumentException.class);
        m.reserve(1L, NOW);
        assertThatThrownBy(() -> m.reserve(2L, NOW)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void unreserveReturnsToChattingAndClearsReservationAndAcceptMarks() {
        ExchangeMatch m = match();
        m.reserve(1L, NOW);
        org.springframework.test.util.ReflectionTestUtils.setField(m, "aCompletedAt", NOW);

        m.unreserve();

        assertThat(m.getStatus()).isEqualTo(ExchangeMatchStatus.CHATTING);
        assertThat(m.getReservedById()).isNull();
        assertThat(m.getReservedAt()).isNull();
        assertThat(m.getACompletedAt()).isNull();
        assertThat(m.getBCompletedAt()).isNull();
        assertThat(m.hasAccepted(Side.A)).isFalse();
        // 같은 쌍이 다시 예약할 수 있다
        m.reserve(2L, NOW.plusMinutes(1));
        assertThat(m.getReservedById()).isEqualTo(2L);
        assertThatThrownBy(match()::unreserve).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void reservedMatchCannotBeCanceledDirectly() {
        ExchangeMatch m = match();
        m.reserve(1L, NOW);

        assertThatThrownBy(() -> m.cancel(1L, NOW)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void cancelRecordsWhoAndWhenAndSystemCancelHasNullActor() {
        ExchangeMatch byUser = match();
        byUser.cancel(2L, NOW);
        assertThat(byUser.getStatus()).isEqualTo(ExchangeMatchStatus.CANCELED);
        assertThat(byUser.getCanceledById()).isEqualTo(2L);
        assertThat(byUser.getCanceledAt()).isEqualTo(NOW);
        assertThat(byUser.isOpen()).isFalse();

        ExchangeMatch bySystem = match();
        bySystem.cancel(null, NOW);
        assertThat(bySystem.getCanceledById()).isNull();
        assertThat(bySystem.getCanceledAt()).isEqualTo(NOW);
    }

    @Test
    void canceledMatchCannotBeCanceledAgain() {
        ExchangeMatch m = match();
        m.cancel(1L, NOW);

        assertThatThrownBy(() -> m.cancel(1L, NOW)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void markAcceptedRecordsOnlyCallersSideAndIsIdempotent() {
        ExchangeMatch m = match();
        m.reserve(1L, NOW);

        m.markAccepted(Side.A, NOW.plusMinutes(1));
        m.markAccepted(Side.A, NOW.plusMinutes(9));   // 두 번째는 시각을 덮어쓰지 않는다

        assertThat(m.getStatus()).isEqualTo(ExchangeMatchStatus.RESERVED);
        assertThat(m.getACompletedAt()).isEqualTo(NOW.plusMinutes(1));
        assertThat(m.getBCompletedAt()).isNull();
        assertThat(m.hasAccepted(Side.A)).isTrue();
        assertThat(m.hasAccepted(Side.B)).isFalse();
        assertThat(m.getReservedById()).isEqualTo(1L);
    }

    @Test
    void markAcceptedRequiresReservedAndAParticipant() {
        ExchangeMatch chatting = match();
        assertThatThrownBy(() -> chatting.markAccepted(Side.A, NOW)).isInstanceOf(IllegalStateException.class);
        ExchangeMatch reserved = match();
        reserved.reserve(1L, NOW);
        assertThatThrownBy(() -> reserved.markAccepted(null, NOW)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void completeClearsReservedColumnsAndKeepsBothAcceptMarks() {
        ExchangeMatch m = match();
        m.reserve(1L, NOW);
        m.markAccepted(Side.A, NOW.plusMinutes(1));
        m.markAccepted(Side.B, NOW.plusMinutes(2));

        m.complete(NOW.plusMinutes(2));

        assertThat(m.getStatus()).isEqualTo(ExchangeMatchStatus.COMPLETED);
        assertThat(m.getReservedById()).isNull();
        assertThat(m.getReservedAt()).isNull();
        assertThat(m.getACompletedAt()).isNotNull();
        assertThat(m.getBCompletedAt()).isNotNull();
        assertThat(m.getTicketAId()).as("매칭 행의 티켓은 교환 전 티켓").isEqualTo(700L);
        assertThat(m.isOpen()).isFalse();
        assertThatThrownBy(() -> m.cancel(1L, NOW)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(m::unreserve).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void completeNeedsReservedAndBothAccepted() {
        ExchangeMatch chatting = match();
        assertThatThrownBy(() -> chatting.complete(NOW)).isInstanceOf(IllegalStateException.class);
        ExchangeMatch oneAccepted = match();
        oneAccepted.reserve(1L, NOW);
        oneAccepted.markAccepted(Side.A, NOW);
        assertThatThrownBy(() -> oneAccepted.complete(NOW)).isInstanceOf(IllegalStateException.class);
    }
}
