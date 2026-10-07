package com.seatswap.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.text.Normalizer;
import java.time.LocalDateTime;

/**
 * 좌석맵 좌표/라벨 저장. Venue 단위로 1회 인식 후 재사용한다 (NFR-03).
 * seatJson 예: [{"row":3,"col":5,"x":187,"y":210,"w":18,"h":18}, ...]
 * (seatmap-recognition-pattern 스킬 / seatmap-service 참고)
 *
 * 상태: DRAFT(사용자가 올린 이미지로 인식한 임시본) → OFFICIAL(관리자 정식 등록).
 * - DRAFT는 공연장 + 구역(zoneName)당 하나 (DB: 생성 컬럼 draft_key UNIQUE, V2).
 * - OFFICIAL은 현재 공연장에 여러 개 허용한다. 추후 '공연장당 1개'로 제한할 예정이라
 *   지금은 제약을 걸지 않는다 (V2 주석 참고).
 * 원본 이미지는 저장하지 않는다 (imageUrl 없음). 좌표 기준 크기만 imageWidth/imageHeight에 둔다.
 */
@Entity
@EntityListeners(AuditingEntityListener.class)
@Getter
@NoArgsConstructor
public class SeatMapLayout {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "venue_id", nullable = false)
    private Venue venue;

    private String zoneName;

    @Lob
    private String seatJson;

    // OCR_PENDING, OCR_DONE 등 — 색상/판매상태 매핑은 하지 않음 (결정사항 참고)
    private String ocrStatus;

    /** varchar 컬럼 (Hibernate 6의 MySQL native enum 매핑 방지). */
    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(nullable = false, length = 20)
    private SeatMapStatus status = SeatMapStatus.DRAFT;

    /**
     * 낙관적 락 버전 (1부터). 레이아웃이 바뀔 때마다(승격, 좌표 수정) 증가하며 V3 revision_no와 겸용한다.
     * 동시 승격/수정은 나중 커밋이 OptimisticLockException으로 실패한다.
     */
    @Version
    @Column(nullable = false)
    private int version = 1;

    /** 좌표 기준 원본 이미지 크기(px). 원본은 저장하지 않는다. */
    private Integer imageWidth;
    private Integer imageHeight;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "created_by", foreignKey = @ForeignKey(name = "fk_seat_map_layout_created_by"))
    private User createdBy;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "promoted_by", foreignKey = @ForeignKey(name = "fk_seat_map_layout_promoted_by"))
    private User promotedBy;

    /** 승격 시각(KST). DRAFT면 null. 호출자가 Clock 기준으로 넘긴다. */
    private LocalDateTime promotedAt;

    /** 저장 시 JPA Auditing이 KST(Clock) 기준으로 채운다 — JpaAuditingConfig. */
    @CreatedDate
    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @LastModifiedDate
    @Column(nullable = false)
    private LocalDateTime updatedAt;

    /**
     * 구역명 정규화: 앞뒤 공백 제거 → NFKC → 연속 공백 한 칸 → 빈 문자열이면 null.
     * DRAFT 유일성(draft_key)이 zone_name 문자열 기준이라, "A구역"/" A구역"/"Ａ구역"이 서로 다른
     * 구역으로 중복 저장되지 않도록 저장 전에 통일한다.
     */
    public static String normalizeZoneName(String zoneName) {
        if (zoneName == null) {
            return null;
        }
        String cleaned = Venue.cleanDisplayText(Normalizer.normalize(zoneName.strip(), Normalizer.Form.NFKC));
        return cleaned == null || cleaned.isEmpty() ? null : cleaned;
    }

    /**
     * 사용자가 올린 이미지를 인식한 결과로 DRAFT 좌석표를 만든다.
     * 입력 검증(최후 방어선, 서비스에서 먼저 400으로 검증): seatJson 필수, 이미지 크기는 있으면 양수, createdBy 필수.
     *
     * TODO(정책 미정): 이미 OFFICIAL 좌석표가 있는 공연장에 같은 구역의 DRAFT를 만들 수 있는지는
     * 아직 정해지지 않았다. 지금은 막지 않는다 (DB 제약도 DRAFT끼리만 유일).
     */
    public static SeatMapLayout createDraft(Venue venue, String zoneName, String seatJson, String ocrStatus,
                                            Integer imageWidth, Integer imageHeight, User createdBy) {
        if (venue == null) {
            throw new IllegalArgumentException("공연장이 필요합니다.");
        }
        if (seatJson == null || seatJson.isBlank()) {
            throw new IllegalArgumentException("좌석 좌표 데이터가 필요합니다.");
        }
        if ((imageWidth != null && imageWidth <= 0) || (imageHeight != null && imageHeight <= 0)) {
            throw new IllegalArgumentException("이미지 크기는 0보다 커야 합니다.");
        }
        if (createdBy == null) {
            throw new IllegalArgumentException("등록자가 필요합니다.");
        }
        SeatMapLayout layout = new SeatMapLayout();
        layout.venue = venue;
        layout.zoneName = normalizeZoneName(zoneName);
        layout.seatJson = seatJson;
        layout.ocrStatus = ocrStatus;
        layout.imageWidth = imageWidth;
        layout.imageHeight = imageHeight;
        layout.createdBy = createdBy;
        layout.status = SeatMapStatus.DRAFT;
        layout.version = 1;
        return layout;
    }

    /**
     * 관리자 정식 등록: DRAFT → OFFICIAL, 그리고 공연장을 VERIFIED로 함께 바꾼다.
     * 정식 등록은 좌석표 등록 시에만 가능하므로 venue VERIFIED와 layout OFFICIAL은 항상 같이 바뀐다 (불변식).
     * 권한은 1차로 보안 계층(/api/admin/**)이 막고, 여기서 admin.role == ADMIN을 한 번 더 확인한다 (이중 방어).
     * 이미 OFFICIAL이면 예외.
     * 공연장이 이미 VERIFIED여도 이 좌석표가 OFFICIAL이 되는 것은 허용한다 (OFFICIAL 복수 허용 결정,
     * 추후 공연장당 1개로 제한할 때 여기서 거부하도록 바꾼다). 이때 공연장의 최초 등록자·시각은 유지된다.
     */
    public void promote(User admin, LocalDateTime now) {
        if (status == SeatMapStatus.OFFICIAL) {
            throw new IllegalStateException("이미 정식 등록된 좌석표입니다.");
        }
        if (admin == null || now == null) {
            throw new IllegalArgumentException("정식 등록에는 관리자와 시각이 필요합니다.");
        }
        if (admin.getRole() != UserRole.ADMIN) {
            throw new IllegalArgumentException("관리자만 좌석표를 정식 등록할 수 있습니다.");
        }
        venue.verify(admin, now);
        this.status = SeatMapStatus.OFFICIAL;
        this.promotedBy = admin;
        this.promotedAt = now;
    }
}
