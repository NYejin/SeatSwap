package com.seatswap.repository;

import com.seatswap.domain.ExchangeMatch;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ExchangeMatchRepository extends JpaRepository<ExchangeMatch, Long> {
    // TODO: 도메인별 조회 메서드 추가
}
