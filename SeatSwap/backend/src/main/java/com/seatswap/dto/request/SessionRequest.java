package com.seatswap.dto.request;

import jakarta.validation.constraints.NotNull;

import java.time.LocalDateTime;

/** 회차 추가/일시 변경. startsAt: KST 현지 시각, 오프셋 없는 ISO 형식("2026-11-01T19:00"), 분 단위 절삭. */
public record SessionRequest(
        @NotNull(message = "회차 일시를 입력해주세요.")
        LocalDateTime startsAt
) {}
