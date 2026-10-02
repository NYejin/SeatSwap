package com.seatswap.repository;

import com.seatswap.domain.ExchangeRequest;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ExchangeRequestRepository extends JpaRepository<ExchangeRequest, Long> {
    // TODO: 도메인별 조회 메서드 추가
}
