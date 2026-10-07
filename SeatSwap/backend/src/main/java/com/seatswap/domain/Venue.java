package com.seatswap.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.text.Normalizer;
import java.time.LocalDateTime;
import java.util.Locale;

/**
 * 공연장. 사용자가 기존 목록에서 검색해 고르고, 없으면 새로 추가한다.
 * SeatMapLayout이 Venue 단위로 재사용되므로(NFR-03) 같은 공연장이 두 번 만들어지지 않도록
 * 정규화한 이름(normalizedName)에 unique 제약을 둔다.
 *
 * 관계: Venue 1:N SeatMapLayout, Venue 1:N Performance (08_ERD). 역방향 컬렉션은 두지 않고
 * 리포지토리 조회로 처리한다.
 *
 * 수정/삭제: 여러 공연·좌석맵이 공유하는 기준 데이터라 일반 사용자 수정·삭제는 허용하지 않는다.
 *
 * 정식 등록(status): 공연 자체에는 상태가 없고 Venue만 UNVERIFIED/VERIFIED를 가진다.
 * 정식 등록은 좌석표 등록 시에만 가능하므로 VERIFIED와 SeatMapLayout OFFICIAL은 항상 같이 바뀐다
 * ({@link SeatMapLayout#promote}가 {@link #verify}를 호출한다).
 */
@Entity
@Table(
        name = "venue",
        uniqueConstraints = @UniqueConstraint(name = "uk_venue_normalized_name", columnNames = "normalized_name")
)
@EntityListeners(AuditingEntityListener.class)
@Getter
@NoArgsConstructor
public class Venue {

    public static final int NAME_MAX_LENGTH = 100;
    public static final int ADDRESS_MAX_LENGTH = 255;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 화면 표시용 이름 (앞뒤 공백 제거, 연속 공백은 한 칸으로). */
    @Column(nullable = false, length = NAME_MAX_LENGTH)
    private String name;

    /** 중복 판정 키. {@link #normalizeName(String)} 결과. */
    @Column(name = "normalized_name", nullable = false, length = NAME_MAX_LENGTH)
    private String normalizedName;

    @Column(length = ADDRESS_MAX_LENGTH)
    private String address;

    /** 저장 시 JPA Auditing이 KST(Clock) 기준으로 채운다 — JpaAuditingConfig. */
    @CreatedDate
    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    /** 정식 등록 상태. varchar 컬럼 (Hibernate 6의 MySQL native enum 매핑 방지). */
    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(nullable = false, length = 20)
    private VenueStatus status = VenueStatus.UNVERIFIED;

    /** 정식 등록한 관리자. UNVERIFIED면 null. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "verified_by", foreignKey = @ForeignKey(name = "fk_venue_verified_by"))
    private User verifiedBy;

    /** 정식 등록 시각(KST). UNVERIFIED면 null. */
    @Column(name = "verified_at")
    private LocalDateTime verifiedAt;

    /**
     * 공연장 생성. 호출 전에 서비스에서 이름/주소 길이와 정규화 결과를 검증해 400으로 응답해야 한다
     * (여기서의 IllegalArgumentException은 최후 방어선).
     */
    public static Venue create(String name, String address) {
        String displayName = cleanDisplayText(name);
        String normalized = normalizeName(displayName);
        if (displayName == null || displayName.isEmpty() || displayName.length() > NAME_MAX_LENGTH) {
            throw new IllegalArgumentException("공연장 이름은 1~" + NAME_MAX_LENGTH + "자여야 합니다.");
        }
        if (normalized.isEmpty() || normalized.length() > NAME_MAX_LENGTH) {
            throw new IllegalArgumentException("공연장 이름에 글자나 숫자가 있어야 합니다.");
        }
        String cleanAddress = cleanDisplayText(address);
        if (cleanAddress != null && cleanAddress.length() > ADDRESS_MAX_LENGTH) {
            throw new IllegalArgumentException("주소는 " + ADDRESS_MAX_LENGTH + "자 이하여야 합니다.");
        }

        Venue venue = new Venue();
        venue.name = displayName;
        venue.normalizedName = normalized;
        venue.address = (cleanAddress == null || cleanAddress.isEmpty()) ? null : cleanAddress;
        return venue;
    }

    /**
     * 정식 등록 처리. 좌석표 승격({@link SeatMapLayout#promote})에서만 호출한다 (package-private) —
     * "정식 등록은 좌석표 등록 시에만 가능"이라는 불변식을 지키기 위해 외부에 열지 않는다.
     * 이미 VERIFIED면 최초 등록자·시각을 유지한다 (OFFICIAL 좌석표는 여러 개 허용).
     * 시각은 호출자가 Clock(Asia/Seoul) 기준으로 넘긴다.
     */
    void verify(User admin, LocalDateTime now) {
        if (admin == null || now == null) {
            throw new IllegalArgumentException("정식 등록에는 관리자와 시각이 필요합니다.");
        }
        if (status == VenueStatus.VERIFIED) {
            return;
        }
        this.status = VenueStatus.VERIFIED;
        this.verifiedBy = admin;
        this.verifiedAt = now;
    }

    public boolean isVerified() {
        return status == VenueStatus.VERIFIED;
    }

    /**
     * 공연장 이름 정규화 (중복 판정·검색 키).
     * 1) NFKC (전각→반각, 호환 문자 통일)
     * 2) 공백·구두점·보이지 않는 문자(Cf/Cc, 한글 채움 문자) 제거
     * 3) 소문자(Locale.ROOT)
     * 예: "KSPO DOME", "kspo-dome", "ＫＳＰＯ ＤＯＭＥ" → "kspodome",
     *     "블루스퀘어 (신한카드홀)" → "블루스퀘어신한카드홀".
     * 띄어쓰기·괄호·하이픈 차이만 흡수한다. 별칭("체조경기장" vs "KSPO DOME")은 합치지 못하므로
     * 등록 전 검색 UI로 막는다. 구두점(P* 범주: · . , ! & # / 괄호 따옴표 하이픈 등)은 제거하고,
     * 기호(S* 범주: + = ~ ★ 등)는 남긴다.
     * null은 빈 문자열로 취급한다.
     */
    public static String normalizeName(String name) {
        if (name == null) {
            return "";
        }
        String nfkc = Normalizer.normalize(name, Normalizer.Form.NFKC);
        StringBuilder sb = new StringBuilder(nfkc.length());
        nfkc.codePoints()
                .filter(cp -> !isIgnorableForKey(cp))
                .forEach(sb::appendCodePoint);
        return sb.toString().toLowerCase(Locale.ROOT);
    }

    private static boolean isIgnorableForKey(int cp) {
        if (Character.isWhitespace(cp)) {
            return true;
        }
        switch (cp) {
            // 한글 채움 문자 (화면에 안 보임)
            case 0x115F, 0x1160, 0x3164, 0xFFA0:
                return true;
            default:
                break;
        }
        return switch (Character.getType(cp)) {
            case Character.SPACE_SEPARATOR, Character.LINE_SEPARATOR, Character.PARAGRAPH_SEPARATOR,
                 Character.FORMAT, Character.CONTROL,
                 Character.CONNECTOR_PUNCTUATION, Character.DASH_PUNCTUATION,
                 Character.START_PUNCTUATION, Character.END_PUNCTUATION,
                 Character.INITIAL_QUOTE_PUNCTUATION, Character.FINAL_QUOTE_PUNCTUATION,
                 Character.OTHER_PUNCTUATION -> true;
            default -> false;
        };
    }

    /**
     * 표시용 텍스트 정리: 앞뒤 공백 제거 + 연속 공백 한 칸. null은 null.
     * 서비스 계층 입력 검증(길이 판정)도 같은 규칙을 써야 하므로 public.
     */
    public static String cleanDisplayText(String value) {
        if (value == null) {
            return null;
        }
        return value.replaceAll("[\\s\\p{Z}\\uFEFF]+", " ").strip();
    }
}
