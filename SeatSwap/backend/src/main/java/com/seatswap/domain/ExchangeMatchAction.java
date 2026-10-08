package com.seatswap.domain;

/** 매칭에 가할 수 있는 동작. 허용 상태 전이 표는 {@link ExchangeMatch#isAllowed} 와 ExchangeMatchService Javadoc 참고. */
public enum ExchangeMatchAction {
    PROPOSE,
    ACCEPT,
    REJECT,
    CANCEL,
    COMPLETE
}
