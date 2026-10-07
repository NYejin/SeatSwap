package com.seatswap.dto.response;

/**
 * 좌석 하나의 좌표. 좌표는 원본 이미지 픽셀 기준 (seat_json 원소와 같은 형식).
 * section: 위에서부터 1.. 인 구획(층). 층이 여럿이면 (row, col)이 구획마다 겹치므로 함께 보존한다.
 * section이 없는(과거에 저장된) seat_json은 0으로 읽혀 1로 채워진다.
 */
public record SeatMapSeat(String uid, int row, int col, int x, int y, int w, int h, int section) {

    public SeatMapSeat {
        if (section < 1) {
            section = 1;
        }
    }

    /** section 없이 만드는 편의 생성자 (section = 1). */
    public SeatMapSeat(String uid, int row, int col, int x, int y, int w, int h) {
        this(uid, row, col, x, y, w, h, 1);
    }
}
