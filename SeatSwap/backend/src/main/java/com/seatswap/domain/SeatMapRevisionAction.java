package com.seatswap.domain;

/** 좌석표 수정 로그의 동작 종류. PROMOTED/REVERTED는 V3 스키마에 예약만 되어 있고 아직 기록하는 코드가 없다. */
public enum SeatMapRevisionAction {
    RECOGNIZED,
    USER_EDIT,
    CORRECTION_APPLIED,
    ADMIN_EDIT,
    PROMOTED,
    REVERTED
}
