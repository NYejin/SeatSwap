package com.seatswap.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 사용자가 보유한 티켓(좌석) 정보. ExchangeRequest와 1:1 관계.
 *
 * 교환은 같은 회차끼리만 가능하므로 공연(Performance)이 아니라 회차(PerformanceSession)를 참조한다.
 * 공연은 performanceSession.performance로 얻는다 — performance_id를 중복으로 두지 않는다
 * (두 FK가 서로 다른 공연을 가리키는 불일치를 원천 차단).
 * seatMapLayout.venue는 performanceSession.performance.venue와 같아야 한다 (티켓 등록 시 서비스에서 검사).
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
    @JoinColumn(name = "seatmap_id", nullable = false)
    private SeatMapLayout seatMapLayout;

    private String rowLabel;
    private String colLabel;
}
