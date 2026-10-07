package com.seatswap.repository;

import com.seatswap.domain.CorrectionStatus;
import com.seatswap.domain.SeatCorrection;
import com.seatswap.domain.SeatMapField;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDateTime;
import java.util.List;

public interface SeatCorrectionRepository extends JpaRepository<SeatCorrection, Long> {

    /** 좌석표를 참조하는 오류 신고 수. TEMP-DRAFT-DELETE: DRAFT 삭제 가능 여부 판정에만 쓴다. */
    long countBySeatMapLayout_Id(Long seatMapId);

    /** 같은 사용자의 같은 정정(좌석·필드·값)이 해당 상태로 이미 있는지 (중복 신고는 PENDING만 본다 — uk_seat_correction_pending_key와 같은 기준). */
    boolean existsBySeatMapLayout_IdAndTargetSeatUidAndTargetFieldAndReporter_IdAndNormalizedValueAndStatus(
            Long seatMapId, String targetSeatUid, SeatMapField targetField, Long reporterId, String normalizedValue,
            CorrectionStatus status);

    /** 같은 좌석·필드의 해당 상태 신고 전부 (정정값 무관) — 자동 반영 집계와 SUPERSEDED 처리용. id 순. */
    List<SeatCorrection> findBySeatMapLayout_IdAndTargetSeatUidAndTargetFieldAndStatusOrderByIdAsc(
            Long seatMapId, String targetSeatUid, SeatMapField targetField, CorrectionStatus status);

    /** 좌석표의 해당 상태 신고 전부 — 관리자 직접 수정 시 대기 신고를 한 번에 무효화하려고 읽는다. */
    List<SeatCorrection> findBySeatMapLayout_IdAndStatus(Long seatMapId, CorrectionStatus status);

    /** 사용자가 since 이후(초과)에 접수한 신고 수 — 일일 신고 한도. */
    long countByReporter_IdAndCreatedAtAfter(Long reporterId, LocalDateTime since);

    /** 사용자의 해당 상태 신고 수 — 대기 신고 상한. */
    long countByReporter_IdAndStatus(Long reporterId, CorrectionStatus status);
}
