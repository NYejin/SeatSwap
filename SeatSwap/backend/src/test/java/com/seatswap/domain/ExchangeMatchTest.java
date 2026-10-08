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

    /** 상태 4종 x 동작 5종 = 20 조합 전부. 서비스 Javadoc의 전이 표와 같아야 한다. */
    @ParameterizedTest(name = "{0} + {1} -> {2}")
    @CsvSource({
            "CHATTING,PROPOSE,false", "CHATTING,ACCEPT,true", "CHATTING,REJECT,true", "CHATTING,CANCEL,true", "CHATTING,COMPLETE,false",
            "RESERVED,PROPOSE,false", "RESERVED,ACCEPT,true", "RESERVED,REJECT,true", "RESERVED,CANCEL,true", "RESERVED,COMPLETE,true",
            "COMPLETED,PROPOSE,true", "COMPLETED,ACCEPT,false", "COMPLETED,REJECT,false", "COMPLETED,CANCEL,false", "COMPLETED,COMPLETE,false",
            "CANCELED,PROPOSE,true", "CANCELED,ACCEPT,false", "CANCELED,REJECT,false", "CANCELED,CANCEL,false", "CANCELED,COMPLETE,false"
    })
    void transitionTable(ExchangeMatchStatus status, ExchangeMatchAction action, boolean allowed) {
        assertThat(ExchangeMatch.isAllowed(status, action)).isEqualTo(allowed);
    }

    @Test
    void proposeStartsChattingWithNothingReservedOrCanceled() {
        ExchangeMatch m = match();

        assertThat(m.getStatus()).isEqualTo(ExchangeMatchStatus.CHATTING);
        assertThat(m.getAReservedAt()).isNull();
        assertThat(m.getBReservedAt()).isNull();
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
    void reservingIsPerSideAndIdempotent() {
        ExchangeMatch m = match();

        m.markReserved(Side.A, NOW);
        m.markReserved(Side.A, NOW.plusMinutes(5));

        assertThat(m.getAReservedAt()).as("두 번째 누름은 시각을 바꾸지 않는다").isEqualTo(NOW);
        assertThat(m.hasReserved(Side.B)).isFalse();
        assertThat(m.bothReserved()).isFalse();
        m.markReserved(Side.B, NOW.plusMinutes(1));
        assertThat(m.bothReserved()).isTrue();
    }

    @Test
    void toReservedNeedsBothSides() {
        ExchangeMatch m = match();
        m.markReserved(Side.A, NOW);

        assertThatThrownBy(m::toReserved).isInstanceOf(IllegalStateException.class);
        m.markReserved(Side.B, NOW);
        m.toReserved();
        assertThat(m.getStatus()).isEqualTo(ExchangeMatchStatus.RESERVED);
        assertThatThrownBy(m::toReserved).isInstanceOf(IllegalStateException.class);
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
}
