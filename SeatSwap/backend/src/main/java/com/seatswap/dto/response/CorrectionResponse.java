package com.seatswap.dto.response;

import com.seatswap.domain.CorrectionStatus;

/** 정정 신고 접수 결과. status는 PENDING(검토 대기) 또는 APPLIED(이 신고로 임계값을 채워 자동 반영됨). */
public record CorrectionResponse(CorrectionStatus status, Long correctionId) {
}
