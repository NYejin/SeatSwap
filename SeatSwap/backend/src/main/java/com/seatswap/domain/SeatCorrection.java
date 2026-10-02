package com.seatswap.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 좌석 인식 오류 신고. 동일한 (좌표, 정정값) 조합이 2건 이상이면 자동 반영,
 * 1건이면 PENDING으로 대기한다 (결정사항: 오류 보정 규칙).
 */
@Entity
@Getter
@NoArgsConstructor
public class SeatCorrection {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "seatmap_id", nullable = false)
    private SeatMapLayout seatMapLayout;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "reporter_id", nullable = false)
    private User reporter;

    private String originalLabel;
    private String correctedLabel;
    private Integer voteCount = 0;

    // PENDING, APPLIED
    private String status = "PENDING";
}
