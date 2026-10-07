package com.seatswap.dto.request;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * 좌석표 라벨 수정. expectedVersion은 클라이언트가 본 좌석표 version (다르면 409 VERSION_CONFLICT).
 * reason은 공백만이거나 보이지 않는 문자(제어·서식 문자, 줄바꿈 포함)가 있으면 서비스가 400 {reason: ...}으로 거부한다.
 */
public record SeatEditRequest(
        @NotNull(message = "좌석표 버전을 입력해주세요.")
        @Min(value = 1, message = "좌석표 버전이 올바르지 않습니다.")
        Integer expectedVersion,

        @NotBlank(message = "수정 사유를 입력해주세요.")
        @Size(max = 500, message = "수정 사유는 500자 이하로 입력해주세요.")
        String reason,

        @NotEmpty(message = "수정할 좌석을 입력해주세요.")
        @Size(max = 2000, message = "한 번에 2000개까지 수정할 수 있습니다.")
        List<@NotNull(message = "수정할 좌석 항목이 비어 있습니다.") @Valid SeatEditChange> changes
) {}
