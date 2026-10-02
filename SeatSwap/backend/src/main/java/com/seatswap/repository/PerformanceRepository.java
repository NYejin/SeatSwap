package com.seatswap.repository;

import com.seatswap.domain.Performance;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PerformanceRepository extends JpaRepository<Performance, Long> {
    // TODO: 도메인별 조회 메서드 추가
}
