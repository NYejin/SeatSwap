package com.seatswap.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.Immutable;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/** 수정 로그 상세: 좌석(seatUid)·필드 하나의 변경 전후 값. append-only (setter 없음, @Immutable). */
@Entity
@Immutable
@Getter
@NoArgsConstructor
public class SeatMapRevisionItem {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "revision_id", nullable = false, updatable = false,
            foreignKey = @ForeignKey(name = "fk_seat_map_revision_item_revision"))
    private SeatMapRevision revision;

    @Column(nullable = false, updatable = false, length = 32)
    private String seatUid;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "field_name", nullable = false, updatable = false, length = 20)
    private SeatMapField field;

    @Column(updatable = false)
    private String beforeValue;

    @Column(updatable = false)
    private String afterValue;

    /** 이 변경을 만든 정정 신고 (자동 반영일 때). */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "correction_id", updatable = false,
            foreignKey = @ForeignKey(name = "fk_seat_map_revision_item_correction"))
    private SeatCorrection correction;

    public static SeatMapRevisionItem of(SeatMapRevision revision, String seatUid, SeatMapField field,
                                         String beforeValue, String afterValue, SeatCorrection correction) {
        if (revision == null || seatUid == null || field == null) {
            throw new IllegalArgumentException("수정 로그, 좌석, 필드가 필요합니다.");
        }
        SeatMapRevisionItem item = new SeatMapRevisionItem();
        item.revision = revision;
        item.seatUid = seatUid;
        item.field = field;
        item.beforeValue = beforeValue;
        item.afterValue = afterValue;
        item.correction = correction;
        return item;
    }
}
