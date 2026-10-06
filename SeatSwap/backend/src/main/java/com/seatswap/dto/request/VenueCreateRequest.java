package com.seatswap.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 공연장 추가 (검색 결과에 없을 때). 정확한 길이 상한(이름 100, 주소 255)은 공백 정리 후 기준으로
 * 서비스에서 검사한다. 여기 @Size는 비정상적으로 긴 입력을 일찍 거르는 1차 방어선.
 */
public record VenueCreateRequest(
        @NotBlank(message = "공연장 이름을 입력해주세요.")
        @Size(max = 300, message = "공연장 이름은 100자 이하로 입력해주세요.")
        String name,

        @Size(max = 600, message = "주소는 255자 이하로 입력해주세요.")
        String address
) {}
