package com.seatswap.repository;

import com.seatswap.domain.ExchangeMatch;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * 잠금 순서 규약: 티켓(id 오름차순) -> 요청(id 오름차순) -> 매칭. 매칭 행 잠금(findByIdForUpdate)은 항상 마지막이다.
 * 이 인터페이스의 벌크 UPDATE/DELETE 는 호출하는 쪽이 이미 티켓·요청 행을 잠근 트랜잭션 안에서만 쓴다.
 */
public interface ExchangeMatchRepository extends JpaRepository<ExchangeMatch, Long> {

    /** 매칭 행 잠금(SELECT ... FOR UPDATE). 티켓·요청 잠금을 모두 잡은 뒤 호출한다. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select m from ExchangeMatch m where m.id = :id")
    Optional<ExchangeMatch> findByIdForUpdate(@Param("id") Long id);

    /** 같은 요청 쌍(방향 무관)의 열린 매칭(CHATTING·RESERVED). uk_exchange_match_open_pair 때문에 최대 1건이다. */
    @Query("""
            select m from ExchangeMatch m
            where ((m.requestAId = :a and m.requestBId = :b) or (m.requestAId = :b and m.requestBId = :a))
              and m.status in (com.seatswap.domain.ExchangeMatchStatus.CHATTING,
                               com.seatswap.domain.ExchangeMatchStatus.RESERVED)
            """)
    List<ExchangeMatch> findOpenByPair(@Param("a") Long requestA, @Param("b") Long requestB);

    /** 이 요청이 참여한 열린 매칭이 있는가 (요청 수정·삭제 차단용). */
    @Query("""
            select count(m) > 0 from ExchangeMatch m
            where (m.requestAId = :requestId or m.requestBId = :requestId)
              and m.status in (com.seatswap.domain.ExchangeMatchStatus.CHATTING,
                               com.seatswap.domain.ExchangeMatchStatus.RESERVED)
            """)
    boolean existsOpenByRequestId(@Param("requestId") Long requestId);

    @Query("""
            select count(m) > 0 from ExchangeMatch m
            where (m.requestAId = :requestId or m.requestBId = :requestId)
              and m.status = com.seatswap.domain.ExchangeMatchStatus.COMPLETED
            """)
    boolean existsCompletedByRequestId(@Param("requestId") Long requestId);

    /** 요청을 삭제할 때 FK 때문에 먼저 지워야 하는 취소된 매칭(사용자에게 남길 기록이 없다). 요청 행 잠금 뒤에 호출한다. */
    @Modifying(flushAutomatically = true)
    @Query("""
            delete from ExchangeMatch m
            where (m.requestAId = :requestId or m.requestBId = :requestId)
              and m.status = com.seatswap.domain.ExchangeMatchStatus.CANCELED
            """)
    int deleteCanceledByRequestId(@Param("requestId") Long requestId);

    /** 티켓을 내릴 때 그 티켓이 a측인 CHATTING 매칭을 시스템 취소(canceled_by NULL)한다. 티켓 행 잠금 뒤에 호출한다. */
    @Modifying(flushAutomatically = true)
    @Query("""
            update ExchangeMatch m
            set m.status = com.seatswap.domain.ExchangeMatchStatus.CANCELED, m.canceledAt = :now, m.updatedAt = :now
            where m.ticketAId = :ticketId and m.status = com.seatswap.domain.ExchangeMatchStatus.CHATTING
            """)
    int cancelChattingByTicketA(@Param("ticketId") Long ticketId, @Param("now") LocalDateTime now);

    /** 티켓을 내릴 때 그 티켓이 b측인 CHATTING 매칭을 시스템 취소한다. */
    @Modifying(flushAutomatically = true)
    @Query("""
            update ExchangeMatch m
            set m.status = com.seatswap.domain.ExchangeMatchStatus.CANCELED, m.canceledAt = :now, m.updatedAt = :now
            where m.ticketBId = :ticketId and m.status = com.seatswap.domain.ExchangeMatchStatus.CHATTING
            """)
    int cancelChattingByTicketB(@Param("ticketId") Long ticketId, @Param("now") LocalDateTime now);
}
