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
 * 상태: CHATTING(채팅, 예약 전) -> RESERVED(한 명이 예약, 두 티켓 잠금) -> COMPLETED(양쪽 교환 수락, 구현 예정), 예약한 사람이든 상대든 누구나
 * RESERVED -> CHATTING 으로 되돌릴 수 있고(unreserve), CHATTING 에서만 취소·거절(CANCELED)된다.
 * 상태 전이는 {@link #isAllowed(ExchangeMatchStatus, ExchangeMatchAction)} 한 곳에서 정의한다.
 * COMPLETED 시점(8차 답변, 2026-10-09)에는 한 트랜잭션에서 기존 두 티켓을 EXCHANGED 로 바꾸고 각자 새 자리 티켓을 만든다는 규칙이 있다(구현 예정, 이번 범위 밖, 서비스 Javadoc 참고).
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

    /** 매칭 시점의 추가금 스냅샷(V6, 표시용). a = a측 희망 범위 중 b 티켓 좌석을 포함한 범위, b = b측이 a 좌석을 포함한 범위의 값. */
    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "a_extra_type", nullable = false, updatable = false, length = 10)
    private ExtraType aExtraType;

    @Column(name = "a_extra_amount", updatable = false)
    private Integer aExtraAmount;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "b_extra_type", nullable = false, updatable = false, length = 10)
    private ExtraType bExtraType;

    @Column(name = "b_extra_amount", updatable = false)
    private Integer bExtraAmount;

    // a_reserved_at / b_reserved_at 는 V8 부터 미사용(레거시) 컬럼이라 매핑하지 않는다.

    /** 예약한 사람(a 또는 b 측 사용자). RESERVED 일 때만 값이 있다(ck_exchange_match_reserved). */
    @Column(name = "reserved_by_id")
    private Long reservedById;

    @Column(name = "reserved_at")
    private LocalDateTime reservedAt;

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

    /** 새 매칭(CHATTING). a = 제안자 쪽. aExtra/bExtra 는 후보 판정에서 읽은 양쪽 적용 추가금(스냅샷)이다. */
    public static ExchangeMatch propose(Long requestAId, Long requestBId, Long ticketAId, Long ticketBId,
                                        Long userAId, Long userBId, WantExtra aExtra, WantExtra bExtra) {
        ExchangeMatch m = new ExchangeMatch();
        m.requestAId = requestAId;
        m.requestBId = requestBId;
        m.ticketAId = ticketAId;
        m.ticketBId = ticketBId;
        m.userAId = userAId;
        m.userBId = userBId;
        m.aExtraType = aExtra.type();
        m.aExtraAmount = aExtra.amount();
        m.bExtraType = bExtra.type();
        m.bExtraAmount = bExtra.amount();
        m.status = ExchangeMatchStatus.CHATTING;
        return m;
    }

    /**
     * 허용 상태 전이 표 (8·9차 답변). PROPOSE 의 상태는 '같은 요청 쌍의 가장 최근 매칭' 상태이며 열린 매칭(CHATTING·RESERVED)이 있으면
     * 새 매칭을 만들 수 없다(취소·완료된 뒤에는 다시 가능). 멱등 처리(RESERVED 에서 reserve, CHATTING 에서 unreserve)는 서비스가 상태를 보고
     * 먼저 걸러내므로 표에서는 '멱등'으로 적고 {@code isAllowed} 는 열린 상태에서 true 를 돌려준다.
     * <pre>
     *            PROPOSE  RESERVE   UNRESERVE  REJECT(b측)  CANCEL  COMPLETE
     * CHATTING   불허     허용       멱등 200   허용         허용    불허(예약 전)
     * RESERVED   불허     멱등 200   허용       불허         불허    허용(미구현)
     * COMPLETED  허용     불허       불허       불허         불허    불허
     * CANCELED   허용     불허       불허       불허         불허    불허
     * </pre>
     * 멱등 200 인 경우(RESERVED 에서 reserve, CHATTING 에서 unreserve)는 허용(true)을 반환하며 서비스가 상태 변경 없이 현재 상태를 응답한다.
     * RESERVED 에서 reject·cancel 은 막고 먼저 예약을 취소해야 한다(409 MATCH_STATE_CONFLICT).
     */
    public static boolean isAllowed(ExchangeMatchStatus status, ExchangeMatchAction action) {
        return switch (action) {
            case PROPOSE -> !status.isOpen();
            case RESERVE, UNRESERVE -> status.isOpen();
            case REJECT, CANCEL -> status == ExchangeMatchStatus.CHATTING;
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

    /** 호출자 쪽 예약: CHATTING -> RESERVED. 두 티켓의 잠금 INSERT 는 서비스가 같은 트랜잭션에서 한다. */
    public void reserve(Long userId, LocalDateTime now) {
        if (status != ExchangeMatchStatus.CHATTING) {
            throw new IllegalStateException("CHATTING 매칭만 예약할 수 있습니다: " + status);
        }
        if (sideOf(userId) == null) {
            throw new IllegalArgumentException("매칭 참여자만 예약할 수 있습니다.");
        }
        status = ExchangeMatchStatus.RESERVED;
        reservedById = userId;
        reservedAt = now;
    }

    /** 예약 취소: RESERVED -> CHATTING. 예약 표시와 교환 수락(a/b_completed_at) 표시를 한 번에 비운다(ck_exchange_match_reserved·completed). */
    public void unreserve() {
        if (status != ExchangeMatchStatus.RESERVED) {
            throw new IllegalStateException("RESERVED 매칭만 예약을 취소할 수 있습니다: " + status);
        }
        status = ExchangeMatchStatus.CHATTING;
        reservedById = null;
        reservedAt = null;
        aCompletedAt = null;
        bCompletedAt = null;
    }

    /** 취소·거절. canceledById 가 null 이면 시스템 취소. CHATTING 만 취소할 수 있다(RESERVED 는 먼저 unreserve). */
    public void cancel(Long canceledById, LocalDateTime now) {
        if (status != ExchangeMatchStatus.CHATTING) {
            throw new IllegalStateException("CHATTING 매칭만 취소할 수 있습니다: " + status);
        }
        status = ExchangeMatchStatus.CANCELED;
        this.canceledById = canceledById;
        canceledAt = now;
    }

    /** 호출자 쪽 교환 수락 표시(a/b_completed_at). 교환 수락 기능은 이번 범위 밖이라 현재는 항상 false 다. */
    public boolean hasAccepted(Side side) {
        return (side == Side.A ? aCompletedAt : bCompletedAt) != null;
    }

    public boolean isOpen() {
        return status.isOpen();
    }
}
