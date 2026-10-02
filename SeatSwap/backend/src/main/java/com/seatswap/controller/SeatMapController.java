package com.seatswap.controller;

import com.seatswap.service.SeatMapService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/seatmaps")
@RequiredArgsConstructor
public class SeatMapController {
    private final SeatMapService seatMapService;
    // TODO: GET /{id} (좌표 JSON 조회), POST /{id}/corrections (오류 신고)
}
