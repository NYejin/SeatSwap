package com.seatswap.dto.response;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.seatswap.domain.SeatMapRevisionAction;
import com.seatswap.domain.SeatMapStatus;

import java.time.LocalDateTime;

/** 좌석표 수정 로그 목록 항목 (ADMIN 전용). actorId가 null이면 시스템 자동 반영. */
public record SeatMapRevisionSummaryResponse(
        Long id,
        int revisionNo,
        SeatMapRevisionAction actionType,
        SeatMapStatus layoutStatus,
        Long actorId,
        String reason,
        @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = DateTimeFormats.SECOND)
        LocalDateTime createdAt,
        long itemCount
) {
}
