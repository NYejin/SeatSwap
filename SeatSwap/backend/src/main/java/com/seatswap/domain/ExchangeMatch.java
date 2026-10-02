package com.seatswap.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import java.time.LocalDateTime;

/** 두 ExchangeRequest(A측/B측)를 연결하는 단순 1:1 매칭 레코드. 추천 점수 컬럼은 추가하지 않는다. */
@Entity
@Getter
@NoArgsConstructor
public class ExchangeMatch {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "request_a_id", nullable = false)
    private ExchangeRequest requestA;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "request_b_id", nullable = false)
    private ExchangeRequest requestB;

    // PROPOSED, ACCEPTED, COMPLETED, CANCELLED
    private String status = "PROPOSED";

    private LocalDateTime matchedAt;
}
