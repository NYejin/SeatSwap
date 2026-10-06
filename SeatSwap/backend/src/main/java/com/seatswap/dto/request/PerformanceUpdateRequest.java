package com.seatswap.dto.request;

import jakarta.validation.constraints.Size;

/** 공연 수정 (등록자만). 보낸 항목만 바꾼다. sourceUrl은 식별 키라 수정 불가(삭제 후 재등록). */
public record PerformanceUpdateRequest(
        @Size(max = 600, message = "공연 제목은 200자 이하로 입력해주세요.")
        String title,

        Long venueId
) {}
