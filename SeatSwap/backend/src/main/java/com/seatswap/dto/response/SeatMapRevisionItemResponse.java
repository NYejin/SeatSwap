package com.seatswap.dto.response;

import com.seatswap.domain.SeatMapField;
import com.seatswap.domain.SeatMapRevisionItem;

/** 수정 로그 상세의 좌석·필드 단위 전후 값. correctionId는 정정 신고 자동 반영일 때만 있다. */
public record SeatMapRevisionItemResponse(
        Long id,
        String seatUid,
        SeatMapField field,
        String beforeValue,
        String afterValue,
        Long correctionId
) {
    public static SeatMapRevisionItemResponse of(SeatMapRevisionItem item) {
        return new SeatMapRevisionItemResponse(item.getId(), item.getSeatUid(), item.getField(),
                item.getBeforeValue(), item.getAfterValue(),
                item.getCorrection() == null ? null : item.getCorrection().getId());
    }
}
