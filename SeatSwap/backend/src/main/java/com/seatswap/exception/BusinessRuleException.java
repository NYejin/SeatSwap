package com.seatswap.exception;

/** 422 Unprocessable Entity. 형식은 맞지만 업무 규칙(상한 등)에 걸린 요청. 응답: {"code": ..., "message": ...} */
public class BusinessRuleException extends SeatSwapException {

    private final String code;

    public BusinessRuleException(String code, String message) {
        super(message);
        this.code = code;
    }

    public String getCode() {
        return code;
    }
}
