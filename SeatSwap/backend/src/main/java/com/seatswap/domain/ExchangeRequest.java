package com.seatswap.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.LocalDateTime;

/**
 * 티켓의 교환 희망 1건 (티켓당 1개, uk_exchange_request_ticket).
 * 추가금은 요청 단위 값이다: X/ANY는 금액 NULL, POS는 금액 > 0, NEG는 금액 < 0 (DB CHECK와 같은 규칙, 매칭 계산에는 쓰지 않는다).
 * 희망 범위·희망 좌석(펼친 결과)·희망 회차는 자식 테이블이 가지며 요청 삭제 시 DB CASCADE로 함께 지워진다.
 */
@Entity
@Table(name = "exchange_request")
@EntityListeners(AuditingEntityListener.class)
@Getter
@NoArgsConstructor
public class ExchangeRequest {

    public static final String UNIQUE_TICKET = "uk_exchange_request_ticket";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "ticket_id", nullable = false, updatable = false)
    private Ticket ticket;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "extra_type", nullable = false, length = 10)
    private ExtraType extraType;

    @Column(name = "extra_amount")
    private Integer extraAmount;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(nullable = false, length = 20)
    private ExchangeRequestStatus status = ExchangeRequestStatus.OPEN;

    @CreatedDate
    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @LastModifiedDate
    @Column(nullable = false)
    private LocalDateTime updatedAt;

    public static ExchangeRequest create(Ticket ticket, ExtraType extraType, Integer extraAmount) {
        if (ticket == null) {
            throw new IllegalArgumentException("ticket은 필수입니다.");
        }
        ExchangeRequest request = new ExchangeRequest();
        request.ticket = ticket;
        request.applyExtra(extraType, extraAmount);
        request.status = ExchangeRequestStatus.OPEN;
        return request;
    }

    /** 추가금 교체. 유형별 금액 규칙을 여기서도 방어한다(서비스가 먼저 필드 오류로 거른다). */
    public void applyExtra(ExtraType extraType, Integer extraAmount) {
        if (extraType == null) {
            throw new IllegalArgumentException("extraType은 필수입니다.");
        }
        boolean valid = switch (extraType) {
            case X, ANY -> extraAmount == null;
            case POS -> extraAmount != null && extraAmount > 0;
            case NEG -> extraAmount != null && extraAmount < 0;
        };
        if (!valid) {
            throw new IllegalArgumentException("추가금 유형과 금액이 맞지 않습니다: " + extraType);
        }
        this.extraType = extraType;
        this.extraAmount = extraAmount;
    }

    /** 희망 범위·회차만 바뀌어도 updated_at 이 갱신되도록 명시적으로 시각을 찍는다(Auditing은 변경 감지된 경우에만 동작). */
    public void touch(LocalDateTime now) {
        this.updatedAt = now;
    }

    public boolean isOpen() {
        return status == ExchangeRequestStatus.OPEN;
    }
}
