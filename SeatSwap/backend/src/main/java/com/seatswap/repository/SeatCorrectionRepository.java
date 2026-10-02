package com.seatswap.repository;

import com.seatswap.domain.SeatCorrection;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SeatCorrectionRepository extends JpaRepository<SeatCorrection, Long> {
    // TODO: 도메인별 조회 메서드 추가
}
