package com.seatswap.dto.request;

import com.seatswap.domain.SeatMapField;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * 정식 좌석표 정정 신고. note는 선택이며 현재는 저장하지 않고 무시된다 (전용 컬럼이 없다 — review_note는 검토자용).
 * API 계약상 받기만 하는 필드이고, 저장이 필요해지면 스키마 설계부터 요청한다.
 */
public record CorrectionRequest(
        @NotBlank(message = "좌석 식별자를 입력해주세요.")
        @Size(max = 32, message = "좌석 식별자가 너무 깁니다.")
        String uid,

        @NotNull(message = "정정할 항목을 선택해주세요.")
        SeatMapField field,

        @NotNull(message = "번호를 입력해주세요.")
        @Min(value = 1, message = "번호는 1 이상이어야 합니다.")
        @Max(value = 9999, message = "번호는 9999 이하여야 합니다.")
        Integer value,

        @Size(max = 500, message = "메모는 500자 이하로 입력해주세요.")
        String note
) {}
