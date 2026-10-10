package com.seatswap.repository;

import com.seatswap.domain.ExchangeHistory;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

/** 교환 이력(append-only). 이번 범위에서는 완료 시 INSERT 와 검증용 조회만 있다(마이페이지 조회 API 는 다음 브랜치). */
public interface ExchangeHistoryRepository extends JpaRepository<ExchangeHistory, Long> {

    List<ExchangeHistory> findByMatchIdOrderByIdAsc(Long matchId);
}
