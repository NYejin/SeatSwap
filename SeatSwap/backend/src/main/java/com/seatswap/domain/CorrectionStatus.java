package com.seatswap.domain;

/** 정정 신고 상태. PENDING=검토 대기, APPLIED=좌석표에 반영됨, REJECTED=관리자 반려, SUPERSEDED=다른 정정이 먼저 반영돼 무효. */
public enum CorrectionStatus {
    PENDING,
    APPLIED,
    REJECTED,
    SUPERSEDED
}
