package com.seatswap.repository;

import com.seatswap.domain.SeatKey;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 펼친 희망 좌석(exchange_want_seat). 최대 수천 행을 한 번에 넣어야 하므로 JPA 엔티티 대신 JdbcTemplate 으로
 * 다중 행 INSERT(청크)를 쓴다. 요청 서비스의 트랜잭션에 그대로 참여한다(같은 DataSource 연결).
 * 호출 전에 JPA 쪽 변경은 flush 되어 있어야 한다(요청 행을 saveAndFlush 한 뒤 호출).
 */
@Repository
public class ExchangeWantSeatRepository {

    /** 한 INSERT 문에 넣는 행 수 (4개 파라미터 x 500 = 2,000). */
    static final int CHUNK_SIZE = 500;

    private final JdbcTemplate jdbc;

    public ExchangeWantSeatRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public int deleteByRequestId(Long requestId) {
        return jdbc.update("DELETE FROM exchange_want_seat WHERE request_id = ?", requestId);
    }

    /** 중복 없는 좌석 집합을 넣는다(PK 충돌은 호출 전에 집합으로 제거해 둔다). */
    public void insertAll(Long requestId, Collection<SeatKey> seats) {
        List<SeatKey> list = new ArrayList<>(seats);
        for (int from = 0; from < list.size(); from += CHUNK_SIZE) {
            List<SeatKey> chunk = list.subList(from, Math.min(from + CHUNK_SIZE, list.size()));
            StringBuilder sql = new StringBuilder(
                    "INSERT INTO exchange_want_seat (request_id, zone_key, row_key, col_key) VALUES ");
            Object[] args = new Object[chunk.size() * 4];
            for (int i = 0; i < chunk.size(); i++) {
                sql.append(i == 0 ? "(?,?,?,?)" : ",(?,?,?,?)");
                SeatKey key = chunk.get(i);
                args[i * 4] = requestId;
                args[i * 4 + 1] = key.zoneKey();
                args[i * 4 + 2] = key.rowKey();
                args[i * 4 + 3] = key.colKey();
            }
            jdbc.update(sql.toString(), args);
        }
    }

    public Map<Long, Integer> countByRequestIds(Collection<Long> requestIds) {
        Map<Long, Integer> counts = new HashMap<>();
        if (requestIds.isEmpty()) {
            return counts;
        }
        String placeholders = String.join(",", requestIds.stream().map(id -> "?").toList());
        jdbc.query("SELECT request_id, COUNT(*) FROM exchange_want_seat WHERE request_id IN (" + placeholders
                        + ") GROUP BY request_id",
                rs -> {
                    counts.put(rs.getLong(1), rs.getInt(2));
                },
                requestIds.toArray());
        return counts;
    }
}
