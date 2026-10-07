package com.seatswap.domain;

/** 좌석표 상태. DRAFT = 사용자가 올린 이미지로 인식한 임시 좌석표, OFFICIAL = 관리자가 정식 등록한 좌석표. */
public enum SeatMapStatus {
    DRAFT,
    OFFICIAL
}
