package com.seatswap.exception;

/** 404 Not Found. 응답: {"message": ...} */
public class NotFoundException extends SeatSwapException {
    public NotFoundException(String message) {
        super(message);
    }
}
