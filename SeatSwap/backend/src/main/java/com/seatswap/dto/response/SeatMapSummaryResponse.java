package com.seatswap.dto.response;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.seatswap.domain.SeatMapStatus;

import java.time.LocalDateTime;

/**
 * 공연장의 좌석표 목록 항목. seat_json을 읽지 않도록 JPQL 생성자 프로젝션으로 만든다.
 * seatCount는 seat_json을 읽어야 해서 제외했다 (필요하면 seat_map_layout.seat_count 컬럼 추가를 설계 요청).
 */
public record SeatMapSummaryResponse(
        Long id,
        String zoneName,
        SeatMapStatus status,
        int version,
        @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = DateTimeFormats.SECOND)
        LocalDateTime createdAt
) {
}
