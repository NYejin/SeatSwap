package com.seatswap.repository;

import com.seatswap.domain.Performance;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.Optional;

public interface PerformanceRepository extends JpaRepository<Performance, Long> {

    /** 쓰기 잠금 조회 (SELECT ... FOR UPDATE). 연관은 fetch하지 않는다 - 공유 행(venue, users)까지 잠그지 않도록. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from Performance p where p.id = :id")
    Optional<Performance> findByIdForUpdate(@Param("id") Long id);

    @Query("select p.id from Performance p where p.sourceKey = :sourceKey")
    Optional<Long> findIdBySourceKey(@Param("sourceKey") String sourceKey);

    /** 상세 조회용 — 공연장·등록자 fetch join (N+1 방지). */
    @Query("""
            select p from Performance p
            join fetch p.venue
            join fetch p.registrant
            where p.id = :id
            """)
    Optional<Performance> findDetailById(@Param("id") Long id);

    /**
     * 목록 검색. 정렬: 다가오는 회차(now 이후 가장 이른 starts_at)가 있는 공연 먼저 그 시각 오름차순,
     * 다가오는 회차가 없는 공연은 뒤로(최근 등록 순).
     * titlePattern은 '!'로 이스케이프한 "%...%" (검색어 없으면 "%"). 대소문자 무시는 컬럼 collation(ci)에 맡긴다.
     * 다음 회차 서브쿼리는 uk_performance_session_performance_starts_at (performance_id, starts_at) 인덱스로 처리된다.
     */
    @Query(value = """
            select p from Performance p
            join fetch p.venue v
            where (:venueId is null or v.id = :venueId)
              and p.title like :titlePattern escape '!'
            order by
              case when (select min(s1.startsAt) from PerformanceSession s1
                         where s1.performance = p and s1.startsAt >= :now) is null then 1 else 0 end,
              (select min(s2.startsAt) from PerformanceSession s2
               where s2.performance = p and s2.startsAt >= :now) asc,
              p.createdAt desc,
              p.id desc
            """,
            countQuery = """
            select count(p) from Performance p
            where (:venueId is null or p.venue.id = :venueId)
              and p.title like :titlePattern escape '!'
            """)
    Page<Performance> search(@Param("venueId") Long venueId,
                             @Param("titlePattern") String titlePattern,
                             @Param("now") LocalDateTime now,
                             Pageable pageable);
}
