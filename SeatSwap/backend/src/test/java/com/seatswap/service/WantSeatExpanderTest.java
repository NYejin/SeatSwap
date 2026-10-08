package com.seatswap.service;

import com.seatswap.domain.SeatKey;
import com.seatswap.dto.request.WantRangeInput;
import com.seatswap.exception.BusinessRuleException;
import com.seatswap.exception.FieldValidationException;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class WantSeatExpanderTest {

    private final WantSeatExpander expander = new WantSeatExpander(50, 5000, 999, 999);

    private static WantRangeInput range(String zone, String rowFrom, String rowTo, String colFrom, String colTo) {
        return new WantRangeInput(zone, rowFrom, rowTo, colFrom, colTo);
    }

    private static FieldValidationException fieldError(WantSeatExpander expander, WantRangeInput... ranges) {
        try {
            expander.expand(List.of(ranges));
        } catch (FieldValidationException e) {
            return e;
        }
        throw new AssertionError("FieldValidationException 이 던져지지 않았습니다.");
    }

    @Test
    void 한_칸_범위는_좌석_1개() {
        WantSeatExpander.Result result = expander.expand(List.of(range("A", "3", "3", "5", "5")));

        assertThat(result.seats()).containsExactly(new SeatKey("A", "3", "5"));
        assertThat(result.ranges()).hasSize(1);
    }

    @Test
    void 열과_번_범위를_직사각형으로_펼친다() {
        WantSeatExpander.Result result = expander.expand(List.of(range("A", "3", "4", "3", "5")));

        assertThat(result.seats()).hasSize(6).contains(
                new SeatKey("A", "3", "3"), new SeatKey("A", "3", "5"),
                new SeatKey("A", "4", "3"), new SeatKey("A", "4", "5"));
    }

    @Test
    void 정규화를_거친_키로_펼친다_구역_공백_제거_대문자_앞0_제거_접미사_제거() {
        WantSeatExpander.Result result = expander.expand(List.of(range(" 1층  a ", "03열", "4열", "7번", "07번")));

        assertThat(result.seats()).containsExactly(new SeatKey("1층A", "3", "7"), new SeatKey("1층A", "4", "7"));
        assertThat(result.ranges().get(0).zoneLabel()).isEqualTo("1층 a");
        assertThat(result.ranges().get(0).rowFrom()).isEqualTo("3");
    }

    @Test
    void 문자_열은_하나씩_번은_범위로_펼친다() {
        WantSeatExpander.Result result = expander.expand(List.of(range("B", "a열", "A", "1", "3")));

        assertThat(result.seats()).containsExactly(
                new SeatKey("B", "A", "1"), new SeatKey("B", "A", "2"), new SeatKey("B", "A", "3"));
    }

    @Test
    void 범위끼리_겹치면_합집합이다() {
        WantSeatExpander.Result result = expander.expand(List.of(
                range("A", "3", "3", "3", "5"),
                range("A", "3", "3", "4", "6"),
                range("a", "3", "3", "5", "5")));

        assertThat(result.seats()).hasSize(4);
        assertThat(result.ranges()).hasSize(3);
    }

    @Test
    void 구역이_다르면_같은_열번이어도_다른_좌석() {
        assertThat(expander.expand(List.of(range("A", "1", "1", "1", "1"), range("B", "1", "1", "1", "1"))).seats())
                .hasSize(2);
    }

    @Test
    void 시작이_끝보다_크면_to_필드_오류() {
        assertThat(fieldError(expander, range("A", "5", "3", "1", "1")).getErrors())
                .containsOnlyKeys("ranges[0].rowTo")
                .containsEntry("ranges[0].rowTo", "열의 끝은 시작보다 크거나 같아야 합니다.");
        assertThat(fieldError(expander, range("A", "1", "1", "9", "2")).getErrors())
                .containsEntry("ranges[0].colTo", "번의 끝은 시작보다 크거나 같아야 합니다.");
    }

    @Test
    void 영이하는_범위_인덱스를_포함한_필드_오류() {
        FieldValidationException e = fieldError(expander,
                range("A", "1", "1", "1", "1"), range("A", "0", "3", "1", "1"), range("A", "1", "1", "-2", "3"));

        assertThat(e.getErrors()).containsEntry("ranges[1].rowFrom", "열은 1 이상이어야 합니다.")
                .containsEntry("ranges[2].colFrom", "번은 부호 없는 숫자(1 이상)로 입력해주세요.")
                .doesNotContainKey("ranges[0].rowFrom");
    }

    @Test
    void 부호_붙은_숫자는_전각과_유니코드_마이너스도_거부한다() {
        for (String signed : new String[]{"-3", "+3", "−3", "－3", "＋3"}) {
            assertThat(fieldError(expander, range("A", signed, signed, "1", "1")).getErrors())
                    .as(signed).containsKeys("ranges[0].rowFrom", "ranges[0].rowTo");
        }
    }

    @Test
    void 숫자와_문자가_섞인_시작끝은_필드_오류() {
        assertThat(fieldError(expander, range("A", "3", "B", "1", "1")).getErrors())
                .containsEntry("ranges[0].rowTo", "열의 시작과 끝은 둘 다 숫자이거나 둘 다 문자여야 합니다.");
        assertThat(fieldError(expander, range("A", "1", "1", "가", "3")).getErrors())
                .containsEntry("ranges[0].colTo", "번의 시작과 끝은 둘 다 숫자이거나 둘 다 문자여야 합니다.");
    }

    @Test
    void 문자_열의_시작끝이_다르면_필드_오류() {
        assertThat(fieldError(expander, range("A", "A", "C", "1", "1")).getErrors())
                .containsEntry("ranges[0].rowTo", "문자 열은 범위로 입력할 수 없습니다. 하나씩 추가해주세요.");
    }

    @Test
    void 숫자_상한을_넘으면_normalizer_메시지가_범위_키로_나온다() {
        assertThat(fieldError(expander, range("A", "1", "1000", "1", "1")).getErrors())
                .containsEntry("ranges[0].rowTo", "열은 999 이하로 입력해주세요.");
    }

    @Test
    void 사용할_수_없는_문자와_번_오류를_한_번에_모은다() {
        FieldValidationException e = fieldError(expander, range("A​", "1", "1", "0", "1"));

        assertThat(e.getErrors()).containsKeys("ranges[0].zone", "ranges[0].colFrom");
    }

    @Test
    void 펼친_좌석이_상한_직전이면_통과하고_초과하면_422와_개수를_알려준다() {
        WantSeatExpander small = new WantSeatExpander(50, 100, 999, 999);

        assertThat(small.expand(List.of(range("A", "1", "10", "1", "10"))).seats()).hasSize(100);

        assertThatThrownBy(() -> small.expand(List.of(range("A", "1", "10", "1", "10"), range("B", "1", "1", "1", "1"))))
                .isInstanceOfSatisfying(BusinessRuleException.class, e -> {
                    assertThat(e.getCode()).isEqualTo("WANT_SEAT_LIMIT_EXCEEDED");
                    assertThat(e.getDetails()).containsEntry("count", 101L).containsEntry("limit", 100L);
                    assertThat(e.getMessage()).contains("100").contains("101");
                });
    }

    @Test
    void 상한은_합집합_기준이라_겹치는_범위는_합이_상한을_넘어도_통과한다() {
        // 원시 합 9990, 합집합 4995
        List<WantRangeInput> overlapping = List.of(range("A", "1", "5", "1", "999"), range("a", "1", "5", "1", "999"));
        assertThat(expander.expand(overlapping).seats()).hasSize(4995);

        // 합집합 계산 정확성: 십자 모양 (3x1 + 1x3 - 겹침 1) = 5
        assertThat(expander.expand(List.of(range("A", "1", "3", "2", "2"), range("A", "2", "2", "1", "3"))).seats())
                .hasSize(5);
        // 5000 경계는 합집합으로도 정확히 5001에서 거부, count 는 합집합 크기
        List<WantRangeInput> over = List.of(range("A", "1", "5", "1", "999"), range("A", "1", "1", "1", "5"),
                range("B", "1", "1", "1", "6"));
        assertThatThrownBy(() -> expander.expand(over)).isInstanceOfSatisfying(BusinessRuleException.class, e ->
                assertThat(e.getDetails()).containsEntry("count", 5001L));
    }

    @Test
    void 기본_상한_5000석_경계() {
        // 5 x 999 = 4995, + 5 = 5000
        List<WantRangeInput> atLimit = List.of(range("A", "1", "5", "1", "999"), range("B", "1", "1", "1", "5"));
        assertThat(expander.expand(atLimit).seats()).hasSize(5000);

        List<WantRangeInput> over = List.of(range("A", "1", "5", "1", "999"), range("B", "1", "1", "1", "6"));
        assertThatThrownBy(() -> expander.expand(over)).isInstanceOfSatisfying(BusinessRuleException.class, e ->
                assertThat(e.getDetails()).containsEntry("count", 5001L).containsEntry("limit", 5000L));
    }

    @Test
    void 상한_검사는_펼치기_전에_하므로_큰_입력도_즉시_거부한다() {
        // 999 x 999 = 998,001석을 펼치지 않고 계산만으로 거부
        assertThatThrownBy(() -> expander.expand(List.of(range("A", "1", "999", "1", "999"))))
                .isInstanceOfSatisfying(BusinessRuleException.class, e ->
                        assertThat(e.getDetails()).containsEntry("count", 998001L));
    }

    @Test
    void 범위_개수_상한_50개_경계() {
        List<WantRangeInput> fifty = new ArrayList<>();
        for (int i = 1; i <= 50; i++) {
            fifty.add(range("A", String.valueOf(i), String.valueOf(i), "1", "1"));
        }
        assertThat(expander.expand(fifty).seats()).hasSize(50);

        fifty.add(range("A", "51", "51", "1", "1"));
        assertThatThrownBy(() -> expander.expand(fifty)).isInstanceOfSatisfying(BusinessRuleException.class, e -> {
            assertThat(e.getCode()).isEqualTo("WANT_RANGE_LIMIT_EXCEEDED");
            assertThat(e.getDetails()).containsEntry("count", 51L).containsEntry("limit", 50L);
        });
    }
}
