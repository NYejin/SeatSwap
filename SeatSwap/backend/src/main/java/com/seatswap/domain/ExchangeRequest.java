package com.seatswap.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import java.time.LocalDateTime;

/** 좌석 교환 희망 등록. 매칭은 알고리즘 추천이 아닌 단순 1:1 신청/수락 (결정사항). */
@Entity
@Getter
@NoArgsConstructor
public class ExchangeRequest {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "ticket_id", nullable = false, unique = true)
    private Ticket ticket;

    private String desiredCondition;

    // 차액 거래 시 추가 금액 (null이면 단순 교환)
    private Integer extraPayment;

    // OPEN, MATCHED, CLOSED
    private String status = "OPEN";

    private LocalDateTime createdAt;
}
