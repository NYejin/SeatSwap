package com.seatswap.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 사용자가 보유한 티켓(좌석) 정보. ExchangeRequest와 1:1 관계.
 *
 * 교환은 같은 공연(Performance)끼리 가능하다 (같은 공연의 다른 회차 티켓끼리도 교환 가능, 2026-10-07 결정).
 * 티켓은 회차(PerformanceSession)를 참조하고, 공연은 performanceSession.performance로 얻는다 — performance_id를 중복으로 두지 않는다
 * (두 FK가 서로 다른 공연을 가리키는 불일치를 원천 차단).
 * seatMapLayout은 null일 수 있다. 선택이다 (좌석표 없이 티켓을 먼저 등록할 수 있다 — seatmap_id NULL 허용, V2).
 * 지정한 경우 seatMapLayout.venue는 performanceSession.performance.venue와 같아야 한다 (티켓 등록 시 서비스에서 검사).
 */
@Entity
@Getter
@NoArgsConstructor
public class Ticket {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "performance_session_id", nullable = false)
    private PerformanceSession performanceSession;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "seatmap_id")
    private SeatMapLayout seatMapLayout;

    private String rowLabel;
    private String colLabel;
}
