package com.seatswap.repository;

import com.seatswap.domain.Ticket;
import com.seatswap.domain.TicketStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface TicketRepository extends JpaRepository<Ticket, Long> {

    long countByUser_IdAndStatus(Long userId, TicketStatus status);

    /** 같은 회차·구역·열·번의 활성 티켓 (uk_ticket_active_seat 와 같은 키). */
    @Query("""
            select t from Ticket t
            where t.performanceSession.id = :sessionId
              and t.zoneKey = :zoneKey and t.rowKey = :rowKey and t.colKey = :colKey
              and t.status = com.seatswap.domain.TicketStatus.ACTIVE
            """)
    Optional<Ticket> findActiveBySeat(@Param("sessionId") Long sessionId,
                                      @Param("zoneKey") String zoneKey,
                                      @Param("rowKey") String rowKey,
                                      @Param("colKey") String colKey);

    /** 내 활성 티켓: 회차 시각 오름차순. 응답 DTO에 필요한 회차·공연을 함께 읽는다. */
    @Query("""
            select t from Ticket t
              join fetch t.performanceSession s
              join fetch s.performance
            where t.user.id = :userId and t.status = com.seatswap.domain.TicketStatus.ACTIVE
            order by s.startsAt asc, t.id asc
            """)
    List<Ticket> findActiveByUser(@Param("userId") Long userId);

    /** 본인 티켓만 조회 — 다른 사람의 티켓은 존재 여부도 드러나지 않게 비어 있다. */
    @Query("""
            select t from Ticket t
              join fetch t.performanceSession s
              join fetch s.performance
            where t.id = :id and t.user.id = :userId
            """)
    Optional<Ticket> findOwned(@Param("id") Long id, @Param("userId") Long userId);
}
