package com.seatswap.service;

import com.seatswap.repository.PerformanceRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/** 공연 등록 + 좌석맵 링크 입력 처리 (FR-02) */
@Service
@RequiredArgsConstructor
public class PerformanceService {
    private final PerformanceRepository performanceRepository;
    // TODO: registerPerformance(), 좌석맵 수집은 SeatMapService/seatmap-service 호출로 위임
}
