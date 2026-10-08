package com.seatswap.dto.response;

import com.fasterxml.jackson.annotation.JsonFormat;

import java.time.LocalDateTime;

/**
 * 호출자 기준으로 본 매칭 (POST 응답, 내 매칭 목록, 단건 조회가 같은 모양). my / counterpart 접두어로 나눠 담고
 * a/b 구분은 mySide(A=제안자, B=제안받은 쪽)와 role(SENT=내가 a측, RECEIVED=내가 b측)로만 알린다. 상대 이메일 등 개인정보는 없고 닉네임만 있다.
 * mySeat / counterpartSeat 의 zone·row·col 은 사용자가 입력한 표시용 원문(label)이다. 완료(COMPLETED) 후 교체가 구현되면 현재 티켓의 자리를 보여주므로
 * 교환 전 자리는 교환 이력 스냅샷(후속)이 담당한다.
 * myExtraType/Amount, counterpartExtraType/Amount 는 각 교환 요청의 추가금 유형(X/ANY/POS/NEG)과 참고용 금액이다(매칭 판정에 금액은 쓰이지 않는다).
 * myReservedAt / counterpartReservedAt 이 null 이 아니면 그쪽이 '이 사람과 교환할게요'를 누른 것이다.
 * status 가 RESERVED 이면 두 티켓이 잠겨 있다. canceledBy 는 CANCELED 일 때만: ME / COUNTERPART / SYSTEM.
 */
public record ExchangeMatchResponse(
        Long id,
        String status,
        String mySide,
        String role,
        Long myRequestId,
        Long myTicketId,
        Seat mySeat,
        Long counterpartRequestId,
        Long counterpartTicketId,
        Seat counterpartSeat,
        String counterpartNickname,
        String myExtraType,
        Integer myExtraAmount,
        String counterpartExtraType,
        Integer counterpartExtraAmount,
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
) {
    /** 좌석 한 곳: 표시용 구역·열·번 + 회차 id·시작 시각. */
    public record Seat(
            String zone,
            String row,
            String col,
            Long sessionId,
            @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = DateTimeFormats.MINUTE)
            LocalDateTime startsAt
    ) {}
}
