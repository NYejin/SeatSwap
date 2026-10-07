package com.seatswap.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.Length;
import org.hibernate.annotations.Immutable;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.LocalDateTime;

/**
 * 좌석표 수정 로그 헤더. append-only: 한 번 저장하면 바꾸거나 지우지 않는다 (setter 없음, @Immutable).
 * 좌석·필드별 전후 값은 {@link SeatMapRevisionItem}에 둔다. revisionNo는 수정 후 SeatMapLayout.version과 같다.
 * actor가 null이면 시스템 자동 반영(정정 신고 N건 이상).
 */
@Entity
@Immutable
@EntityListeners(AuditingEntityListener.class)
@Getter
@NoArgsConstructor
public class SeatMapRevision {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "seatmap_id", nullable = false, updatable = false,
            foreignKey = @ForeignKey(name = "fk_seat_map_revision_seatmap"))
    private SeatMapLayout seatMapLayout;

    @Column(nullable = false, updatable = false)
    private int revisionNo;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(nullable = false, updatable = false, length = 30)
    private SeatMapRevisionAction actionType;

    /** 수정 시점의 좌석표 상태. */
    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(nullable = false, updatable = false, length = 20)
    private SeatMapStatus layoutStatus;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "actor_id", updatable = false, foreignKey = @ForeignKey(name = "fk_seat_map_revision_actor"))
    private User actor;

    @Column(updatable = false, length = 500)
    private String reason;

    // @Lob + @Column은 length를 명시하지 않으면 255(tinytext)로 기대해 validate가 실패한다 -> LONG32(longtext)
    @Lob
    @Column(updatable = false, length = Length.LONG32)
    private String beforeJson;

    @Lob
    @Column(updatable = false, length = Length.LONG32)
    private String afterJson;

    /** 저장 시 JPA Auditing이 KST(Clock) 기준으로 채운다 — JpaAuditingConfig. */
    @CreatedDate
    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    /** 로그 한 건을 만든다. before/afterJson은 전체 교체일 때만 넣는다 (라벨 수정은 null). */
    public static SeatMapRevision of(SeatMapLayout layout, int revisionNo, SeatMapRevisionAction actionType,
                                     User actor, String reason, String beforeJson, String afterJson) {
        if (layout == null || actionType == null) {
            throw new IllegalArgumentException("좌석표와 동작 종류가 필요합니다.");
        }
        SeatMapRevision revision = new SeatMapRevision();
        revision.seatMapLayout = layout;
        revision.revisionNo = revisionNo;
        revision.actionType = actionType;
        revision.layoutStatus = layout.getStatus();
        revision.actor = actor;
        revision.reason = reason;
        revision.beforeJson = beforeJson;
        revision.afterJson = afterJson;
        return revision;
    }
}
