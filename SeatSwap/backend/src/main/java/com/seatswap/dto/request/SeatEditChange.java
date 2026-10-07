package com.seatswap.dto.request;

import com.seatswap.domain.SeatMapField;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** 좌석 하나의 라벨 변경. field는 ROW_LABEL/COL_LABEL만 허용 (서비스에서 검사). */
public record SeatEditChange(
        @NotBlank(message = "좌석 식별자를 입력해주세요.")
        @Size(max = 32, message = "좌석 식별자가 너무 깁니다.")
        String uid,

        @NotNull(message = "수정할 항목을 선택해주세요.")
        SeatMapField field,

        @NotNull(message = "번호를 입력해주세요.")
        @Min(value = 1, message = "번호는 1 이상이어야 합니다.")
        @Max(value = 9999, message = "번호는 9999 이하여야 합니다.")
        Integer value
) {}
