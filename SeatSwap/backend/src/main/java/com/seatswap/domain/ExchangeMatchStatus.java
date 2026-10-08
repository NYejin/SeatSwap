package com.seatswap.domain;

/**
 * CHATTING = 후보를 골라 채팅을 시작한 상태(예약 대기), RESERVED = 양쪽이 '이 사람과 교환할게요'를 눌러 두 티켓이 잠긴 상태,
 * COMPLETED = 양쪽 '교환 완료'(이번 범위 밖), CANCELED = 취소·거절(사용자 또는 시스템). 열린 매칭 = CHATTING 또는 RESERVED.
 */
public enum ExchangeMatchStatus {
    CHATTING,
    RESERVED,
    COMPLETED,
    CANCELED;

    public boolean isOpen() {
        return this == CHATTING || this == RESERVED;
    }
}
