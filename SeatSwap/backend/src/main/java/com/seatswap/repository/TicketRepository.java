package com.seatswap.repository;

import com.seatswap.domain.Ticket;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TicketRepository extends JpaRepository<Ticket, Long> {

    /** 공연의 모든 회차에 등록된 티켓 수 (공연장 변경·공연 삭제 가능 여부 판정). */
    long countByPerformanceSession_Performance_Id(Long performanceId);

    /** 회차에 등록된 티켓 수 (회차 수정·삭제 가능 여부 판정). */
    long countByPerformanceSession_Id(Long performanceSessionId);

    /** 좌석표를 참조하는 티켓 수. TEMP-DRAFT-DELETE: DRAFT 삭제 가능 여부 판정에만 쓴다. */
    long countBySeatMapLayout_Id(Long seatMapId);
}
