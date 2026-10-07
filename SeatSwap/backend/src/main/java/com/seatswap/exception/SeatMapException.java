package com.seatswap.exception;

import org.springframework.http.HttpStatus;

/**
 * 좌석표 등록/인식 과정의 오류. 응답: {"code": ..., "message": ...} + status.
 * seatmap-service가 준 code는 가능하면 유지한다. message는 사용자에게 보여도 되는 문구만 담는다
 * (내부 URL·스택 등은 넣지 않는다).
 */
public class SeatMapException extends SeatSwapException {

    private final HttpStatus status;
    private final String code;

    public SeatMapException(HttpStatus status, String code, String message) {
        super(message);
        this.status = status;
        this.code = code;
    }

    public HttpStatus getStatus() {
        return status;
    }

    public String getCode() {
        return code;
    }
}
