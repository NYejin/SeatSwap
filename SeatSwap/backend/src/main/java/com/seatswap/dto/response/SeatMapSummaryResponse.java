package com.seatswap.dto.response;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.seatswap.domain.SeatMapStatus;

import java.time.LocalDateTime;

/**
 * 공연장의 좌석표 목록 항목. seat_json을 읽지 않도록 JPQL 생성자 프로젝션으로 만든다.
 * seatCount는 seat_map_layout.seat_count 컬럼(V3)에서 읽는다.
 */
public record SeatMapSummaryResponse(
        Long id,
        String zoneName,
        SeatMapStatus status,
        int version,
        int seatCount,
        @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = DateTimeFormats.SECOND)
        LocalDateTime createdAt
) {
}
