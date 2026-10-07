package com.seatswap.domain;

/**
 * 수정 로그 항목이 가리키는 좌석 필드. 현재 수정·정정 API가 다루는 것은 ROW_LABEL/COL_LABEL뿐이고
 * 나머지(좌표·좌석 추가/삭제)는 스키마에 예약되어 있다.
 */
public enum SeatMapField {
    ROW_LABEL,
    COL_LABEL,
    X,
    Y,
    W,
    H,
    SEAT_ADDED,
    SEAT_REMOVED;

    /** 사용자·관리자가 수정하거나 정정 신고할 수 있는 필드(라벨)인가. */
    public boolean isEditableLabel() {
        return this == ROW_LABEL || this == COL_LABEL;
    }
}
