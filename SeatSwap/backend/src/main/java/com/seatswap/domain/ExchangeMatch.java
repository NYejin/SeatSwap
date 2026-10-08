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
 * 후보를 골라 시작한 매칭(채팅방) 1건 (설계 1.7). a = 후보 목록에서 상대를 고른 쪽(제안자), b = 고른 상대.
 * 연관 엔티티 대신 id 만 가진다(요청·티켓·사용자는 서비스가 잠그고 읽는다).
 * request_low_id / request_high_id / open_flag 는 DB 생성 컬럼이라 매핑하지 않는다
 * (uk_exchange_match_open_pair 가 '같은 요청 쌍의 열린 매칭 1개'를 보장한다).
 *
 * 상태 전이는 {@link #isAllowed(ExchangeMatchStatus, ExchangeMatchAction)} 한 곳에서 정의한다.
 * 티켓 좌석·회차 갱신은 COMPLETED 시점에 한 트랜잭션에서 두 티켓을 교체한다는 규칙이 있다(이번 범위 밖, 서비스 Javadoc 참고).
 */
@Entity
@Table(name = "exchange_match")
@EntityListeners(AuditingEntityListener.class)
@Getter
@NoArgsConstructor
public class ExchangeMatch {

    public static final String UNIQUE_OPEN_PAIR = "uk_exchange_match_open_pair";

    /** 매칭에서 호출자가 서 있는 쪽. */
    public enum Side { A, B }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "request_a_id", nullable = false, updatable = false)
    private Long requestAId;

    @Column(name = "request_b_id", nullable = false, updatable = false)
    private Long requestBId;

    @Column(name = "ticket_a_id", nullable = false, updatable = false)
    private Long ticketAId;

    @Column(name = "ticket_b_id", nullable = false, updatable = false)
    private Long ticketBId;

    @Column(name = "user_a_id", nullable = false, updatable = false)
    private Long userAId;

    @Column(name = "user_b_id", nullable = false, updatable = false)
    private Long userBId;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(nullable = false, length = 20)
    private ExchangeMatchStatus status = ExchangeMatchStatus.CHATTING;

    @Column(name = "a_reserved_at")
    private LocalDateTime aReservedAt;

    @Column(name = "b_reserved_at")
    private LocalDateTime bReservedAt;

    @Column(name = "a_completed_at")
    private LocalDateTime aCompletedAt;

    @Column(name = "b_completed_at")
    private LocalDateTime bCompletedAt;

    /** CANCELED 일 때만. NULL 이면 시스템 취소(티켓 내림 등). */
    @Column(name = "canceled_by_id")
    private Long canceledById;

    @Column(name = "canceled_at")
    private LocalDateTime canceledAt;

    @CreatedDate
    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @LastModifiedDate
    @Column(nullable = false)
    private LocalDateTime updatedAt;

    /** 새 매칭(CHATTING). a = 제안자 쪽. */
    public static ExchangeMatch propose(Long requestAId, Long requestBId, Long ticketAId, Long ticketBId,
                                        Long userAId, Long userBId) {
        ExchangeMatch m = new ExchangeMatch();
        m.requestAId = requestAId;
        m.requestBId = requestBId;
        m.ticketAId = ticketAId;
        m.ticketBId = ticketBId;
        m.userAId = userAId;
        m.userBId = userBId;
        m.status = ExchangeMatchStatus.CHATTING;
        return m;
    }

    /**
     * 허용 상태 전이 표. PROPOSE 의 상태는 '같은 요청 쌍의 가장 최근 매칭' 상태이며 열린 매칭(CHATTING·RESERVED)이 있으면
     * 새 매칭을 만들 수 없다(취소·완료된 뒤에는 다시 가능).
     * <pre>
     *            PROPOSE  ACCEPT      REJECT(b측)  CANCEL  COMPLETE
     * CHATTING   불허     허용        허용         허용    불허(예약 전)
     * RESERVED   불허     멱등 200    허용         허용    허용(미구현)
     * COMPLETED  허용     불허        불허         불허    불허
     * CANCELED   허용     불허        불허         불허    불허
     * </pre>
     */
    public static boolean isAllowed(ExchangeMatchStatus status, ExchangeMatchAction action) {
        return switch (action) {
            case PROPOSE -> !status.isOpen();
            case ACCEPT, REJECT, CANCEL -> status.isOpen();
            case COMPLETE -> status == ExchangeMatchStatus.RESERVED;
        };
    }

    public Side sideOf(Long userId) {
        if (userId == null) {
            return null;
        }
        if (userId.equals(userAId)) {
            return Side.A;
        }
        return userId.equals(userBId) ? Side.B : null;
    }

    public LocalDateTime reservedAt(Side side) {
        return side == Side.A ? aReservedAt : bReservedAt;
    }

    public boolean hasReserved(Side side) {
        return reservedAt(side) != null;
    }

    public boolean bothReserved() {
        return aReservedAt != null && bReservedAt != null;
    }

    /** 호출자 쪽 '이 사람과 교환할게요'. 이미 눌렀다면 아무 일도 하지 않는다(멱등). */
    public void markReserved(Side side, LocalDateTime now) {
        if (hasReserved(side)) {
            return;
        }
        if (side == Side.A) {
            aReservedAt = now;
        } else {
            bReservedAt = now;
        }
    }

    /** 양쪽이 모두 눌렀을 때 RESERVED 로 전환한다(두 티켓의 잠금 INSERT 는 서비스가 같은 트랜잭션에서 한다). */
    public void toReserved() {
        if (status != ExchangeMatchStatus.CHATTING || !bothReserved()) {
            throw new IllegalStateException("양쪽이 예약에 동의한 CHATTING 매칭만 RESERVED 가 될 수 있습니다.");
        }
        status = ExchangeMatchStatus.RESERVED;
    }

    /** 취소·거절. canceledById 가 null 이면 시스템 취소. 열린 매칭만 취소할 수 있다. */
    public void cancel(Long canceledById, LocalDateTime now) {
        if (!status.isOpen()) {
            throw new IllegalStateException("열린 매칭만 취소할 수 있습니다: " + status);
        }
        status = ExchangeMatchStatus.CANCELED;
        this.canceledById = canceledById;
        canceledAt = now;
    }

    public boolean isOpen() {
        return status.isOpen();
    }
}
