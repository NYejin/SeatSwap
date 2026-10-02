package com.seatswap.service;

import com.seatswap.repository.SeatMapLayoutRepository;
import com.seatswap.repository.SeatCorrectionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * 좌석맵 저장/조회 + 오류 신고 처리 (FR-03~07).
 * 실제 이미지 인식(OpenCV/OCR)은 이 서비스가 아니라 seatmap-service(FastAPI)가 담당하며,
 * 이 클래스는 그 결과 좌표 JSON을 받아 SeatMapLayout에 저장하는 역할만 한다
 * (seatmap-vision-engineer 에이전트는 seatmap-service 쪽 작업 전담).
 */
@Service
@RequiredArgsConstructor
public class SeatMapService {
    private final SeatMapLayoutRepository seatMapLayoutRepository;
    private final SeatCorrectionRepository seatCorrectionRepository;

    // TODO: saveRecognitionResult(), reportCorrection() — 동일 정정 2건 이상 시 자동 반영 로직
}
