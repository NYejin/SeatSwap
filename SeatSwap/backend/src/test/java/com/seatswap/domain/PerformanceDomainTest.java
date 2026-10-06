package com.seatswap.domain;

import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 공연장 이름 정규화(중복 판정 키), 공연/회차 생성 규칙. */
class PerformanceDomainTest {

    @Test
    void 공연장_이름_정규화는_공백_구두점_대소문자_전각을_흡수한다() {
        assertThat(Venue.normalizeName("KSPO DOME")).isEqualTo("kspodome");
        assertThat(Venue.normalizeName("kspo-dome")).isEqualTo("kspodome");
        assertThat(Venue.normalizeName("ＫＳＰＯ　ＤＯＭＥ")).isEqualTo("kspodome");
        assertThat(Venue.normalizeName("  블루스퀘어 (신한카드홀) ")).isEqualTo("블루스퀘어신한카드홀");
        assertThat(Venue.normalizeName("블루스퀘어·신한카드홀")).isEqualTo("블루스퀘어신한카드홀");
    }

    @Test
    void 공연장_이름_정규화는_보이지_않는_문자와_구두점을_제거하고_기호는_남긴다() {
        assertThat(Venue.normalizeName("올림픽\u200B홀\uFEFF")).isEqualTo("올림픽홀");
        assertThat(Venue.normalizeName("\u3164")).isEmpty();
        assertThat(Venue.normalizeName("A&B 홀")).isEqualTo("ab홀");
        assertThat(Venue.normalizeName("홀+ 2관")).isEqualTo("홀+2관");
        assertThat(Venue.normalizeName(null)).isEmpty();
    }

    @Test
    void 공연장_생성시_표시명은_공백만_정리하고_정규화키를_채운다() {
        Venue venue = Venue.create("  KSPO   DOME ", "  ");

        assertThat(venue.getName()).isEqualTo("KSPO DOME");
        assertThat(venue.getNormalizedName()).isEqualTo("kspodome");
        assertThat(venue.getAddress()).isNull();
        assertThat(venue.getCreatedAt()).isNull(); // 저장 시 Auditing이 채운다
    }

    @Test
    void 공연장_이름이_구두점뿐이면_생성할_수_없다() {
        assertThatThrownBy(() -> Venue.create("( - )", null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Venue.create("   ", null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Venue.create("가".repeat(Venue.NAME_MAX_LENGTH + 1), null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void 공연_생성과_등록자_판정() {
        User registrant = User.create("a@b.com", "encoded", "닉네임");
        Venue venue = Venue.create("KSPO DOME", null);

        Performance performance = Performance.create(venue, "  콘서트  ", " https://example.com/goods/1 ",
                "example:1", registrant);

        assertThat(performance.getTitle()).isEqualTo("콘서트");
        assertThat(performance.getSourceUrl()).isEqualTo("https://example.com/goods/1");
        assertThat(performance.getSourceKey()).isEqualTo("example:1");
        assertThat(performance.isRegisteredBy(null)).isFalse();
        assertThatThrownBy(() -> Performance.create(venue, " ", "https://x", "x", registrant))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Performance.create(venue, "t", "https://x", " ", registrant))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void 회차_일시는_분_단위로_잘린다() {
        User registrant = User.create("a@b.com", "encoded", "닉네임");
        Performance performance = Performance.create(Venue.create("KSPO DOME", null), "콘서트",
                "https://example.com/goods/1", "example:1", registrant);

        PerformanceSession session = PerformanceSession.create(performance,
                LocalDateTime.of(2026, 12, 24, 19, 0, 30, 123));

        assertThat(session.getStartsAt()).isEqualTo(LocalDateTime.of(2026, 12, 24, 19, 0));
        session.reschedule(LocalDateTime.of(2026, 12, 25, 18, 0, 59));
        assertThat(session.getStartsAt()).isEqualTo(LocalDateTime.of(2026, 12, 25, 18, 0));
        assertThatThrownBy(() -> PerformanceSession.create(performance, null))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
