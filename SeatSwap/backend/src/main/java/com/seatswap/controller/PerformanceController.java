package com.seatswap.controller;

import com.seatswap.service.PerformanceService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/performances")
@RequiredArgsConstructor
public class PerformanceController {
    private final PerformanceService performanceService;
    // TODO: POST /  (공연 등록 + 좌석맵 링크 입력)
}
