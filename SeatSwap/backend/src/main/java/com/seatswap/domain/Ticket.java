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

    private String rowLabel;
    private String colLabel;
}
