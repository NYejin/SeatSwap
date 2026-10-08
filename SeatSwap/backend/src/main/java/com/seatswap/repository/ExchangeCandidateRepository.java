package com.seatswap.repository;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 매칭 후보 조회 (설계 exchange-schema-design.md 2절 SQL). 네이티브 SQL 한 번의 조인으로 닉네임·회차까지 읽어 N+1이 없다.
 * 접근 경로: 내 요청(PK) -> 내 희망 회차 x 내 희망 좌석(exchange_want_seat PK 앞부분) -> 상대 티켓
 * (uk_ticket_active_seat 점조회) -> 상대 요청(uk_exchange_request_ticket) -> 상대 희망 회차/좌석(PK 점조회).
 *
 * 판정: 같은 공연, 상대 티켓의 회차 ∈ 내 희망 회차, 내 티켓의 회차 ∈ 상대 희망 회차, 상대 좌석 ∈ 내 희망 좌석, 내 좌석 ∈ 상대 희망 좌석,
 * 추가금 유형 호환(불성립은 POS-POS, POS-X 뿐; 금액은 쓰지 않음), 상대 티켓 ACTIVE·상대 요청 OPEN·상대 회차 마감 전·다른 사용자.
 * 정렬: 내 희망 회차 우선순위 -> 상대 요청 최신 -> 요청 id 내림차순. 점수·랭킹 없음.
 */
@Repository
public class ExchangeCandidateRepository {

    /** 후보 한 행. 엔티티가 아니라 조회 전용 값이다. */
    public record Row(Long requestId, Long ticketId, String zone, String row, String col,
                      Long sessionId, LocalDateTime startsAt, String nickname, int wantPriority,
                      String extraType, Integer extraAmount, String myExtraType, Integer myExtraAmount,
                      LocalDateTime requestedAt) {}

    /**
     * 판정에 필요한 조인. 목록과 COUNT가 공유한다(조인 순서는 STRAIGHT_JOIN 으로 고정).
     * psa 는 FK 로 항상 존재하지만 `psb.performance_id = psa.performance_id`(같은 공연 방어)에 쓰이므로 COUNT 에서도 뺄 수 없다.
     */
    private static final String FROM_CORE = """
            FROM exchange_request a
            JOIN ticket ta                 ON ta.id = a.ticket_id
            JOIN performance_session psa   ON psa.id = ta.performance_session_id
            JOIN exchange_want_session wsa ON wsa.request_id = a.id
            JOIN performance_session psb   ON psb.id = wsa.performance_session_id
                                          AND psb.performance_id = psa.performance_id
            JOIN exchange_want_seat wa     ON wa.request_id = a.id
            JOIN ticket tb                 ON tb.performance_session_id = wsa.performance_session_id
                                          AND tb.zone_key = wa.zone_key
                                          AND tb.row_key  = wa.row_key
                                          AND tb.col_key  = wa.col_key
                                          AND tb.active_flag = 1
            JOIN exchange_request b        ON b.ticket_id = tb.id AND b.status = 'OPEN'
            JOIN exchange_want_session wsb ON wsb.request_id = b.id
                                          AND wsb.performance_session_id = ta.performance_session_id
            JOIN exchange_want_seat wb     ON wb.request_id = b.id
                                          AND wb.zone_key = ta.zone_key
                                          AND wb.row_key  = ta.row_key
                                          AND wb.col_key  = ta.col_key
            """;

    /** 닉네임을 읽기 위한 조인. users 는 tb.user_id FK 로 항상 존재하므로 결과 행 수에 영향이 없어 COUNT 에서는 생략한다. */
    private static final String JOIN_USERS = """
            JOIN users ub                  ON ub.id = tb.user_id
            """;

    private static final String WHERE = """
            WHERE a.id = ? AND a.status = 'OPEN'
              AND tb.user_id <> ta.user_id
              AND NOT (a.extra_type = 'POS' AND b.extra_type IN ('POS', 'X'))
              AND NOT (b.extra_type = 'POS' AND a.extra_type IN ('POS', 'X'))
              AND psb.starts_at >= ?
            """;

    /**
     * STRAIGHT_JOIN: FROM 절의 순서(내 요청 -> 내 희망 회차·좌석 -> 상대 티켓 -> 상대 요청 ...)대로 조인하도록 고정한다.
     * 힌트가 없으면 요청 수가 적을 때 옵티마이저가 exchange_request(b)를 전체 스캔으로 시작해 작업량이 전체 요청 수에 비례한다
     * (EXPLAIN 확인, README 검증 결과). 고정하면 작업량이 내 희망 좌석 수 x 희망 회차 수에만 비례한다.
     */
    private static final String SELECT = """
            SELECT STRAIGHT_JOIN b.id AS request_id, tb.id AS ticket_id, tb.zone_label, tb.row_label, tb.col_label,
                   psb.id AS session_id, psb.starts_at, ub.nickname, wsa.priority AS want_priority,
                   b.extra_type, b.extra_amount, a.extra_type AS my_extra_type, a.extra_amount AS my_extra_amount,
                   b.created_at
            """;

    private static final String ORDER = """
            ORDER BY wsa.priority ASC, b.created_at DESC, b.id DESC
            LIMIT ? OFFSET ?
            """;

    private final JdbcTemplate jdbc;

    public ExchangeCandidateRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 후보에서 더 제외할 조건을 붙이는 확장 지점. 지금은 해당 테이블이 없어 비어 있다.
     * V5(차단·매칭·예약 잠금 테이블) 때 이 메서드만 채운다. 정확한 조건식은 backend/README.md '후속 메모 (매칭 후보 조회)' 참고.
     * 규칙(위반하면 IllegalStateException): 조각은 `AND`로 시작하고 파라미터(`?`)를 포함하지 않는다.
     * ta/tb/a/b 별칭을 쓸 수 있다. 앞뒤 개행은 {@link #normalizeExclusions} 가 보장한다.
     */
    static String additionalExclusions() {
        return normalizeExclusions("");
    }

    /** 확장 조각 검증·정규화. 비어 있으면 빈 문자열, 아니면 앞뒤에 개행을 붙인다. */
    static String normalizeExclusions(String fragment) {
        if (fragment == null || fragment.isBlank()) {
            return "";
        }
        String trimmed = fragment.strip();
        if (!trimmed.regionMatches(true, 0, "AND", 0, 3) || (trimmed.length() > 3 && Character.isLetterOrDigit(trimmed.charAt(3)))) {
            throw new IllegalStateException("후보 제외 조각은 AND 로 시작해야 합니다.");
        }
        if (trimmed.indexOf('?') >= 0) {
            throw new IllegalStateException("후보 제외 조각에는 바인딩 파라미터(?)를 쓸 수 없습니다.");
        }
        return "\n" + trimmed + "\n";
    }

    /** 현재 목록 SQL(확장 지점 포함). 실행계획 확인용. */
    public static String candidateSql() {
        return SELECT + FROM_CORE + JOIN_USERS + WHERE + additionalExclusions() + ORDER;
    }

    /** COUNT 전용 SQL(users 조인 생략). 결과 행 수는 목록 쿼리 전체와 같아야 한다(테스트로 확인). */
    public static String countSql() {
        return "SELECT COUNT(*) " + FROM_CORE + WHERE + additionalExclusions();
    }

    /**
     * @param todayStart 오늘 0시(KST). 상대 회차의 마감(starts_at 다음날 0시)이 지나지 않은 것은 starts_at >= todayStart 와 같다.
     */
    public List<Row> findCandidates(Long myRequestId, LocalDateTime todayStart, int limit, long offset) {
        return jdbc.query(candidateSql(), (rs, i) -> new Row(
                rs.getLong("request_id"), rs.getLong("ticket_id"),
                rs.getString("zone_label"), rs.getString("row_label"), rs.getString("col_label"),
                rs.getLong("session_id"), rs.getObject("starts_at", LocalDateTime.class),
                rs.getString("nickname"), rs.getInt("want_priority"),
                rs.getString("extra_type"), (Integer) rs.getObject("extra_amount"),
                rs.getString("my_extra_type"), (Integer) rs.getObject("my_extra_amount"),
                rs.getObject("created_at", LocalDateTime.class)),
                myRequestId, todayStart, limit, offset);
    }

    public long countCandidates(Long myRequestId, LocalDateTime todayStart) {
        Long count = jdbc.queryForObject(countSql(), Long.class, myRequestId, todayStart);
        return count == null ? 0 : count;
    }
}
