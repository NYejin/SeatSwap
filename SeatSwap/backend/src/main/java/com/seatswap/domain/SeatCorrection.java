package com.seatswap.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.LocalDateTime;

/**
 * 좌석 정정 신고 (OFFICIAL 좌석표 전용). 1신고 = 1행이고 건수는 집계한다.
 * 같은 (좌석표, 좌석 uid, 필드, 정정값)의 대기(PENDING) 신고가 서로 다른 신고자 N건(기본 2, 최소 2) 이상이면 자동 반영(APPLIED),
 * 그 미만이면 PENDING으로 대기한다 (결정사항: 오류 보정 규칙). 같은 사용자의 같은 정정은 대기 중에는 중복 접수되지 않는다
 * (uk_seat_correction_pending_key, 생성 컬럼 — 종결된 뒤에는 다시 신고할 수 있다).
 * 좌석이 다른 경로(관리자 수정, 다른 정정의 자동 반영)로 바뀌면 같은 좌석·필드의 대기 신고는 SUPERSEDED가 된다.
 */
@Entity
@EntityListeners(AuditingEntityListener.class)
@Getter
@NoArgsConstructor
public class SeatCorrection {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "seatmap_id", nullable = false)
    private SeatMapLayout seatMapLayout;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "reporter_id", nullable = false)
    private User reporter;

    /** 정정 전(신고 시점) 값과 정정값의 표시용 문자열. */
    private String originalLabel;
    private String correctedLabel;

    @Column(nullable = false, length = 32)
    private String targetSeatUid;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(nullable = false, length = 20)
    private SeatMapField targetField;

    /** 정정값 정규화 (중복·집계 키). 라벨은 정수이므로 10진수 문자열. */
    @Column(nullable = false, length = 100)
    private String normalizedValue;

    /** 신고 시점 SeatMapLayout.version. */
    @Column(nullable = false)
    private int layoutVersion;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(nullable = false, length = 20)
    private CorrectionStatus status = CorrectionStatus.PENDING;

    /** 저장 시 JPA Auditing이 KST(Clock) 기준으로 채운다 — JpaAuditingConfig. */
    @CreatedDate
    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "reviewed_by", foreignKey = @ForeignKey(name = "fk_seat_correction_reviewed_by"))
    private User reviewedBy;

    private LocalDateTime reviewedAt;

    @Column(length = 500)
    private String reviewNote;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "applied_revision_id", foreignKey = @ForeignKey(name = "fk_seat_correction_applied_revision"))
    private SeatMapRevision appliedRevision;

    /** 새 신고(PENDING)를 만든다. 신고자의 메모(note)는 전용 컬럼이 없어 저장하지 않는다. */
    public static SeatCorrection report(SeatMapLayout layout, User reporter, String seatUid, SeatMapField field,
                                        int originalValue, int correctedValue) {
        if (layout == null || reporter == null || seatUid == null || field == null || !field.isEditableLabel()) {
            throw new IllegalArgumentException("좌석표, 신고자, 좌석, 라벨 필드가 필요합니다.");
        }
        SeatCorrection correction = new SeatCorrection();
        correction.seatMapLayout = layout;
        correction.reporter = reporter;
        correction.targetSeatUid = seatUid;
        correction.targetField = field;
        correction.originalLabel = Integer.toString(originalValue);
        correction.correctedLabel = Integer.toString(correctedValue);
        correction.normalizedValue = normalize(correctedValue);
        correction.layoutVersion = layout.getVersion();
        correction.status = CorrectionStatus.PENDING;
        return correction;
    }

    public static String normalize(int value) {
        return Integer.toString(value);
    }

    /** 자동 반영됨. 시스템 반영이라 reviewedBy는 비운다. */
    public void markApplied(SeatMapRevision revision) {
        requirePending();
        this.status = CorrectionStatus.APPLIED;
        this.appliedRevision = revision;
    }

    /** 같은 좌석·필드에 다른 정정이 먼저 반영돼 더 이상 유효하지 않다. */
    public void markSuperseded() {
        requirePending();
        this.status = CorrectionStatus.SUPERSEDED;
    }

    /** 자동 반영 조건을 채웠지만 반영하지 못해 관리자 확인이 필요함을 표시한다. reviewed_*는 건드리지 않는다. */
    public void holdForReview(String note) {
        requirePending();
        this.reviewNote = note;
    }

    private void requirePending() {
        if (status != CorrectionStatus.PENDING) {
            throw new IllegalStateException("검토 대기 상태의 신고만 처리할 수 있습니다.");
        }
    }
}
