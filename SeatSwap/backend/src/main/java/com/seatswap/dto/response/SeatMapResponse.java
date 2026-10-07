package com.seatswap.dto.response;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.seatswap.domain.SeatMapLayout;
import com.seatswap.domain.SeatMapStatus;

import java.time.LocalDateTime;
import java.util.List;

/** 좌석표 상세. 좌표 기준은 imageWidth x imageHeight 픽셀의 원본 이미지 (원본은 저장하지 않는다). */
public record SeatMapResponse(
        Long id,
        Long venueId,
        String venueName,
        String zoneName,
        SeatMapStatus status,
        int version,
        Integer imageWidth,
        Integer imageHeight,
        List<SeatMapSeat> seats,
        @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = DateTimeFormats.SECOND)
        LocalDateTime createdAt,
        @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = DateTimeFormats.SECOND)
        LocalDateTime updatedAt
) {
    public static SeatMapResponse of(SeatMapLayout layout, List<SeatMapSeat> seats) {
        return new SeatMapResponse(layout.getId(), layout.getVenue().getId(), layout.getVenue().getName(),
                layout.getZoneName(), layout.getStatus(), layout.getVersion(),
                layout.getImageWidth(), layout.getImageHeight(), seats,
                layout.getCreatedAt(), layout.getUpdatedAt());
    }
}
