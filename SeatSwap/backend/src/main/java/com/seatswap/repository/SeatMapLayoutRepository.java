package com.seatswap.repository;

import com.seatswap.domain.SeatMapLayout;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SeatMapLayoutRepository extends JpaRepository<SeatMapLayout, Long> {
    // TODO: 도메인별 조회 메서드 추가
}
