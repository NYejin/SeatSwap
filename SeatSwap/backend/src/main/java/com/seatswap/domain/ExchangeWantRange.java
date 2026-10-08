package com.seatswap.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 사용자가 입력한 희망 범위 1건 (수정 화면 복원용). 펼친 개별 좌석은 exchange_want_seat 가 가진다.
 * 구역은 범위 대상이 아니며 label(표시용)/key(비교용)를 둔다. 열·번의 from/to 에는 정규화 키를 저장한다
 * (숫자 범위는 from~to, 문자는 from=to).
 */
@Entity
@Table(name = "exchange_want_range")
@Getter
@NoArgsConstructor
public class ExchangeWantRange {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "request_id", nullable = false, updatable = false)
    private Long requestId;

    @Column(name = "zone_label", nullable = false, length = Ticket.ZONE_MAX_LENGTH)
    private String zoneLabel;

    @Column(name = "zone_key", nullable = false, length = Ticket.ZONE_MAX_LENGTH)
    private String zoneKey;

    @Column(name = "row_from", nullable = false, length = Ticket.ROW_MAX_LENGTH)
    private String rowFrom;

    @Column(name = "row_to", nullable = false, length = Ticket.ROW_MAX_LENGTH)
    private String rowTo;

    @Column(name = "col_from", nullable = false, length = Ticket.COL_MAX_LENGTH)
    private String colFrom;

    @Column(name = "col_to", nullable = false, length = Ticket.COL_MAX_LENGTH)
    private String colTo;

    @Column(name = "sort_order", nullable = false)
    private Short sortOrder;

    public static ExchangeWantRange create(Long requestId, String zoneLabel, String zoneKey,
                                           String rowFrom, String rowTo, String colFrom, String colTo, int sortOrder) {
        ExchangeWantRange range = new ExchangeWantRange();
        range.requestId = requestId;
        range.zoneLabel = zoneLabel;
        range.zoneKey = zoneKey;
        range.rowFrom = rowFrom;
        range.rowTo = rowTo;
        range.colFrom = colFrom;
        range.colTo = colTo;
        range.sortOrder = (short) sortOrder;
        return range;
    }
}
