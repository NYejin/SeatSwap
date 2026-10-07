package com.seatswap.dto.request;

import jakarta.validation.constraints.Size;

/**
 * 공연 수정 (등록자만). 제목만 바꾼다. sourceUrl은 식별 키, 공연장 이름(venueName)은 링크 자동 입력 대상이라
 * 수정 불가(삭제 후 재등록). 요청에 venueName 등 알 수 없는 필드가 있어도 무시한다.
 */
public record PerformanceUpdateRequest(
        @Size(max = 600, message = "공연 제목은 200자 이하로 입력해주세요.")
        String title
) {}
