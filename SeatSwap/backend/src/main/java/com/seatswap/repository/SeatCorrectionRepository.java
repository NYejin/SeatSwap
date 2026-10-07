package com.seatswap.repository;

import com.seatswap.domain.SeatCorrection;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SeatCorrectionRepository extends JpaRepository<SeatCorrection, Long> {
    // TODO: 도메인별 조회 메서드 추가

    /** 좌석표를 참조하는 오류 신고 수. TEMP-DRAFT-DELETE: DRAFT 삭제 가능 여부 판정에만 쓴다. */
    long countBySeatMapLayout_Id(Long seatMapId);
}
