package com.seatswap.service;

import com.seatswap.repository.ExchangeRequestRepository;
import com.seatswap.repository.ExchangeMatchRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/** 교환 요청 등록/조회, 1:1 신청·수락 (FR-09, FR-10). 알고리즘 매칭 없음 — 단순 신청/수락만. */
@Service
@RequiredArgsConstructor
public class ExchangeService {
    private final ExchangeRequestRepository exchangeRequestRepository;
    private final ExchangeMatchRepository exchangeMatchRepository;
    // TODO: createRequest(), applyToRequest(), acceptMatch()
}
