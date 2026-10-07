package com.seatswap.domain;

import org.junit.jupiter.api.Test;

import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 좌석표 DRAFT → OFFICIAL 승격과 공연장 정식 등록(VERIFIED)이 함께 바뀌는 불변식. */
class SeatMapPromotionTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 10, 7, 12, 0);

    private final User admin = adminUser("admin@b.com");

    private static User adminUser(String email) {
        User user = User.create(email, "encoded", "관리자");
        ReflectionTestUtils.setField(user, "role", UserRole.ADMIN); // DB에서 수동 부여한 상황
        return user;
    }

    @Test
    void 신규_공연장은_UNVERIFIED이고_가입_사용자는_USER다() {
        Venue venue = Venue.create("KSPO DOME", null);

        assertThat(venue.getStatus()).isEqualTo(VenueStatus.UNVERIFIED);
        assertThat(venue.isVerified()).isFalse();
        assertThat(venue.getVerifiedBy()).isNull();
        assertThat(venue.getVerifiedAt()).isNull();
        assertThat(User.create("u@b.com", "encoded", "닉네임").getRole()).isEqualTo(UserRole.USER);
    }

    @Test
    void verify는_상태와_등록자_시각을_채운다() {
        Venue venue = Venue.create("KSPO DOME", null);

        venue.verify(admin, NOW);

        assertThat(venue.getStatus()).isEqualTo(VenueStatus.VERIFIED);
        assertThat(venue.getVerifiedBy()).isSameAs(admin);
        assertThat(venue.getVerifiedAt()).isEqualTo(NOW);
    }

    @Test
    void verify는_이미_등록된_공연장의_최초_등록자와_시각을_유지한다() {
        Venue venue = Venue.create("KSPO DOME", null);
        venue.verify(admin, NOW);

        venue.verify(adminUser("other@b.com"), NOW.plusDays(1));

        assertThat(venue.getVerifiedBy()).isSameAs(admin);
        assertThat(venue.getVerifiedAt()).isEqualTo(NOW);
    }

    @Test
    void verify는_관리자와_시각이_없으면_거부한다() {
        Venue venue = Venue.create("KSPO DOME", null);

        assertThatThrownBy(() -> venue.verify(null, NOW)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> venue.verify(admin, null)).isInstanceOf(IllegalArgumentException.class);
        assertThat(venue.isVerified()).isFalse();
    }

    @Test
    void DRAFT_좌석표는_버전1_DRAFT로_만들어진다() {
        SeatMapLayout layout = draftLayout(Venue.create("KSPO DOME", null));

        assertThat(layout.getStatus()).isEqualTo(SeatMapStatus.DRAFT);
        assertThat(layout.getVersion()).isEqualTo(1);
        assertThat(layout.getImageWidth()).isEqualTo(800);
        assertThat(layout.getPromotedBy()).isNull();
        assertThat(layout.getPromotedAt()).isNull();
    }

    @Test
    void promote는_좌석표를_OFFICIAL로_공연장을_VERIFIED로_함께_바꾼다() {
        Venue venue = Venue.create("KSPO DOME", null);
        SeatMapLayout layout = draftLayout(venue);

        layout.promote(admin, NOW);

        assertThat(layout.getStatus()).isEqualTo(SeatMapStatus.OFFICIAL);
        assertThat(layout.getPromotedBy()).isSameAs(admin);
        assertThat(layout.getPromotedAt()).isEqualTo(NOW);
        assertThat(venue.getStatus()).isEqualTo(VenueStatus.VERIFIED);
        assertThat(venue.getVerifiedBy()).isSameAs(admin);
        assertThat(venue.getVerifiedAt()).isEqualTo(NOW);
    }

    @Test
    void 같은_공연장의_다른_좌석표도_승격할_수_있다_OFFICIAL_복수_허용() {
        Venue venue = Venue.create("KSPO DOME", null);
        SeatMapLayout first = draftLayout(venue);
        SeatMapLayout second = draftLayout(venue);

        first.promote(admin, NOW);
        second.promote(admin, NOW.plusHours(1));

        assertThat(second.getStatus()).isEqualTo(SeatMapStatus.OFFICIAL);
        assertThat(venue.getVerifiedAt()).isEqualTo(NOW); // 최초 정식 등록 유지
    }

    @Test
    void 이미_OFFICIAL이면_다시_승격할_수_없다() {
        SeatMapLayout layout = draftLayout(Venue.create("KSPO DOME", null));
        layout.promote(admin, NOW);

        assertThatThrownBy(() -> layout.promote(admin, NOW.plusDays(1)))
                .isInstanceOf(IllegalStateException.class);
        assertThat(layout.getPromotedAt()).isEqualTo(NOW);
    }

    @Test
    void 관리자나_시각이_없으면_승격이_거부되고_아무것도_바뀌지_않는다() {
        Venue venue = Venue.create("KSPO DOME", null);
        SeatMapLayout layout = draftLayout(venue);

        assertThatThrownBy(() -> layout.promote(null, NOW)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> layout.promote(admin, null)).isInstanceOf(IllegalArgumentException.class);
        assertThat(layout.getStatus()).isEqualTo(SeatMapStatus.DRAFT);
        assertThat(venue.isVerified()).isFalse();
    }

    @Test
    void 일반_USER는_승격할_수_없고_아무것도_바뀌지_않는다() {
        Venue venue = Venue.create("KSPO DOME", null);
        SeatMapLayout layout = draftLayout(venue);
        User normalUser = User.create("u@b.com", "encoded", "닉네임");

        assertThatThrownBy(() -> layout.promote(normalUser, NOW)).isInstanceOf(IllegalArgumentException.class);
        assertThat(layout.getStatus()).isEqualTo(SeatMapStatus.DRAFT);
        assertThat(layout.getPromotedBy()).isNull();
        assertThat(venue.isVerified()).isFalse();
    }

    @Test
    void 구역명은_trim_NFKC_공백정리_후_빈값이면_null로_저장된다() {
        Venue venue = Venue.create("KSPO DOME", null);

        assertThat(zone(venue, "  Ａ구역   1층 ").getZoneName()).isEqualTo("A구역 1층");
        assertThat(zone(venue, "A구역").getZoneName()).isEqualTo("A구역");
        assertThat(zone(venue, "   ").getZoneName()).isNull();
        assertThat(zone(venue, "　").getZoneName()).isNull();
        assertThat(zone(venue, null).getZoneName()).isNull();
    }

    @Test
    void createDraft는_필수_입력과_이미지_크기를_검증한다() {
        Venue venue = Venue.create("KSPO DOME", null);

        assertThatThrownBy(() -> SeatMapLayout.createDraft(venue, "A", null, "OCR_DONE", 800, 600, admin))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> SeatMapLayout.createDraft(venue, "A", "  ", "OCR_DONE", 800, 600, admin))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> SeatMapLayout.createDraft(venue, "A", "[]", "OCR_DONE", 0, 600, admin))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> SeatMapLayout.createDraft(venue, "A", "[]", "OCR_DONE", 800, -1, admin))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> SeatMapLayout.createDraft(venue, "A", "[]", "OCR_DONE", 800, 600, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> SeatMapLayout.createDraft(null, "A", "[]", "OCR_DONE", 800, 600, admin))
                .isInstanceOf(IllegalArgumentException.class);
        // 이미지 크기는 없어도 된다
        assertThat(SeatMapLayout.createDraft(venue, "A", "[]", "OCR_DONE", null, null, admin).getImageWidth()).isNull();
    }

    private SeatMapLayout zone(Venue venue, String zoneName) {
        return SeatMapLayout.createDraft(venue, zoneName, "[]", "OCR_DONE", 800, 600, admin);
    }

    private SeatMapLayout draftLayout(Venue venue) {
        return SeatMapLayout.createDraft(venue, "1층", "[]", "OCR_DONE", 800, 600, admin);
    }
}
