package com.seatswap.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 좌석맵 좌표/라벨 저장. Venue 단위로 1회 인식 후 재사용한다 (NFR-03).
 * seatJson 예: [{"row":3,"col":5,"x":187,"y":210,"w":18,"h":18}, ...]
 * (seatmap-recognition-pattern 스킬 / seatmap-service 참고)
 */
@Entity
@Getter
@NoArgsConstructor
public class SeatMapLayout {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "venue_id", nullable = false)
    private Venue venue;

    private String zoneName;

    private String imageUrl;

    @Lob
    private String seatJson;

    // OCR_PENDING, OCR_DONE 등 — 색상/판매상태 매핑은 하지 않음 (결정사항 참고)
    private String ocrStatus;
}
