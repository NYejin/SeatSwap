package com.seatswap.exception;

/**
 * 특정 입력 필드에 귀속되는 비즈니스 검증 실패 (예: 이메일 중복).
 * GlobalExceptionHandler가 @Valid 실패와 같은 {field: message} 형태로 응답한다.
 */
public class FieldValidationException extends SeatSwapException {

    private final String field;

    public FieldValidationException(String field, String message) {
        super(message);
        this.field = field;
    }

    public String getField() {
        return field;
    }
}
