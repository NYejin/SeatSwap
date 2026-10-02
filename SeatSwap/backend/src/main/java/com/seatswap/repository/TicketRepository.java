package com.seatswap.repository;

import com.seatswap.domain.Ticket;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TicketRepository extends JpaRepository<Ticket, Long> {
    // TODO: 도메인별 조회 메서드 추가
}
