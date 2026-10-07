package com.seatswap.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.Collate;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.LocalDateTime;

/**
 * 공연 (FR-02). 로그인한 사용자 누구나 티켓팅 사이트 링크(sourceUrl)를 입력해 등록한다.
 * 날짜·시간은 회차(PerformanceSession)로 분리했다 — Performance 1:N PerformanceSession.
 *
 * 중복 방지: 티켓팅 사이트의 상품 하나 = 공연 하나이므로 링크를 정규화한 sourceKey에 unique 제약을 둔다.
 * sourceKey 계산 규칙(사이트별 상품 ID 추출 → "{site}:{productId}", 미지원 사이트는 일반 URL 정규화)은
 * 서비스 계층에서 수행한 뒤 넘긴다. "공연장+제목"은 같은 공연장에서 같은 제목으로 다시 열리는 공연이
 * 있어 unique로 쓰지 않는다(서비스에서 유사 공연 안내용으로만 사용).
 *
 * 수정/삭제 정책: 등록자만. 제목·공연장 수정 가능(공연장은 티켓이 없을 때만 — 서비스에서 검사),
 * sourceUrl/sourceKey는 식별 키라 수정 불가(잘못 넣었으면 삭제 후 재등록). 삭제는 하위 회차에 티켓이
 * 하나도 없을 때만.
 */
@Entity
@Table(
        name = "performance",
        uniqueConstraints = @UniqueConstraint(name = "uk_performance_source_key", columnNames = "source_key")
)
@EntityListeners(AuditingEntityListener.class)
@Getter
@NoArgsConstructor
public class Performance {

    public static final int TITLE_MAX_LENGTH = 200;
    public static final int SOURCE_URL_MAX_LENGTH = 2048;
    public static final int SOURCE_KEY_MAX_LENGTH = 500;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "venue_id", nullable = false)
    private Venue venue;

    @Column(nullable = false, length = TITLE_MAX_LENGTH)
    private String title;

    /** 사용자가 입력한 티켓팅 사이트 링크 원문 (공연 중복 판정의 기준). */
    @Column(name = "source_url", nullable = false, length = SOURCE_URL_MAX_LENGTH)
    private String sourceUrl;

    /**
     * 중복 판정 키 (정규화된 링크). 생성 후 변경 불가.
     * collation은 utf8mb4_bin(대소문자·악센트 구분): URL 경로·쿼리는 대소문자를 구분하므로 테이블 기본값
     * utf8mb4_0900_ai_ci를 쓰면 대소문자만 다른 링크가 같은 공연으로 합쳐진다. 키에 비ASCII(인코딩 안 된
     * 경로, 디코딩된 상품 ID)가 들어올 수 있어 ascii_bin이 아니라 utf8mb4_bin을 쓴다.
     */
    @Collate("utf8mb4_bin")
    @Column(name = "source_key", nullable = false, updatable = false, length = SOURCE_KEY_MAX_LENGTH)
    private String sourceKey;

    /** 등록자. 수정/삭제 권한 판정에 쓴다. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "registrant_id", nullable = false, updatable = false)
    private User registrant;

    /** 생성/수정 시각은 JPA Auditing이 KST(Clock) 기준으로 채운다 — JpaAuditingConfig. */
    @CreatedDate
    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    /** 필드 변경이 flush될 때(@PreUpdate) 자동 갱신. 생성 시에는 createdAt과 같은 값. */
    @LastModifiedDate
    @Column(nullable = false)
    private LocalDateTime updatedAt;

    /**
     * 공연 생성. 길이·형식 검증과 sourceKey 계산은 서비스에서 먼저 수행해 400으로 응답해야 한다
     * (여기서의 IllegalArgumentException은 최후 방어선).
     */
    public static Performance create(Venue venue, String title, String sourceUrl, String sourceKey, User registrant) {
        requireNonNull(venue, "venue");
        requireNonNull(registrant, "registrant");
        requireText(sourceUrl, SOURCE_URL_MAX_LENGTH, "sourceUrl");
        requireText(sourceKey, SOURCE_KEY_MAX_LENGTH, "sourceKey");

        Performance performance = new Performance();
        performance.venue = venue;
        performance.title = cleanTitle(title);
        performance.sourceUrl = sourceUrl.strip();
        performance.sourceKey = sourceKey;
        performance.registrant = registrant;
        return performance;
    }

    /** 제목 수정 (등록자 권한은 서비스에서 확인). */
    public void changeTitle(String title) {
        this.title = cleanTitle(title);
    }

    /**
     * 공연장 수정. 회차에 등록된 티켓이 있으면 티켓의 좌석 기준(공연장)과 어긋나므로
     * 서비스에서 "티켓 0건"을 확인한 뒤에만 호출한다.
     */
    public void changeVenue(Venue venue) {
        requireNonNull(venue, "venue");
        this.venue = venue;
    }

    /** 등록자 여부. LAZY 프록시의 id만 읽으므로 추가 쿼리가 나가지 않는다. */
    public boolean isRegisteredBy(Long userId) {
        return userId != null && registrant != null && userId.equals(registrant.getId());
    }

    private static String cleanTitle(String title) {
        String cleaned = Venue.cleanDisplayText(title);
        if (cleaned == null || cleaned.isEmpty() || cleaned.length() > TITLE_MAX_LENGTH) {
            throw new IllegalArgumentException("공연 제목은 1~" + TITLE_MAX_LENGTH + "자여야 합니다.");
        }
        return cleaned;
    }

    private static void requireText(String value, int maxLength, String field) {
        if (value == null || value.isBlank() || value.strip().length() > maxLength) {
            throw new IllegalArgumentException(field + "는 1~" + maxLength + "자여야 합니다.");
        }
    }

    private static void requireNonNull(Object value, String field) {
        if (value == null) {
            throw new IllegalArgumentException(field + "는 필수입니다.");
        }
    }
}
