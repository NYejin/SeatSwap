package com.seatswap.dto.response;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.seatswap.domain.SeatMapRevision;
import com.seatswap.domain.SeatMapRevisionAction;
import com.seatswap.domain.SeatMapStatus;

import java.time.LocalDateTime;
import java.util.List;

/** 좌석표 수정 로그 상세 (ADMIN 전용): 헤더 + 변경 항목. before/after 전체 JSON은 내려주지 않는다. */
public record SeatMapRevisionDetailResponse(
        Long id,
        int revisionNo,
        SeatMapRevisionAction actionType,
        SeatMapStatus layoutStatus,
        Long actorId,
        String reason,
        @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = DateTimeFormats.SECOND)
        LocalDateTime createdAt,
        List<SeatMapRevisionItemResponse> items
) {
    public static SeatMapRevisionDetailResponse of(SeatMapRevision revision, List<SeatMapRevisionItemResponse> items) {
        return new SeatMapRevisionDetailResponse(revision.getId(), revision.getRevisionNo(), revision.getActionType(),
                revision.getLayoutStatus(), revision.getActor() == null ? null : revision.getActor().getId(),
                revision.getReason(), revision.getCreatedAt(), items);
    }
}
