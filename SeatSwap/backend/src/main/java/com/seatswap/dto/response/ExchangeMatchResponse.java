package com.seatswap.dto.response;

import com.fasterxml.jackson.annotation.JsonFormat;

import java.time.LocalDateTime;

/**
 * 호출자 기준으로 본 매칭. my / counterpart 접두어로 나눠 담고 a/b 구분(제안자 여부)은 mySide 로만 알린다.
 * myReservedAt / counterpartReservedAt 이 null 이 아니면 그쪽이 '이 사람과 교환할게요'를 누른 것이다.
 * status 가 RESERVED 이면 두 티켓이 잠겨 있다. canceledBy 는 CANCELED 일 때만: ME / COUNTERPART / SYSTEM.
 */
public record ExchangeMatchResponse(
        Long id,
        String status,
        String mySide,
        Long myRequestId,
        Long myTicketId,
        Long counterpartRequestId,
        Long counterpartTicketId,
        String counterpartNickname,
        @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = DateTimeFormats.SECOND)
        LocalDateTime myReservedAt,
        @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = DateTimeFormats.SECOND)
        LocalDateTime counterpartReservedAt,
        String canceledBy,
        @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = DateTimeFormats.SECOND)
        LocalDateTime canceledAt,
        @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = DateTimeFormats.SECOND)
        LocalDateTime createdAt,
        @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = DateTimeFormats.SECOND)
        LocalDateTime updatedAt
) {}
