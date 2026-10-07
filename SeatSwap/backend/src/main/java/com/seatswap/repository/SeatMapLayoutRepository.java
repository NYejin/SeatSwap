package com.seatswap.repository;

import com.seatswap.domain.SeatMapLayout;
import com.seatswap.domain.SeatMapStatus;
import com.seatswap.dto.response.SeatMapSummaryResponse;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface SeatMapLayoutRepository extends JpaRepository<SeatMapLayout, Long> {

    /** 상세 조회: 공연장을 함께 읽는다 (open-in-view=false라 트랜잭션 밖에서 lazy 접근 불가). */
    @Query("select l from SeatMapLayout l join fetch l.venue where l.id = :id")
    Optional<SeatMapLayout> findDetailById(@Param("id") Long id);

    /** 구역이 있는 DRAFT의 id (공연장 + 구역당 하나 — uk_seat_map_layout_draft_key). seat_json은 읽지 않는다. */
    @Query("""
            select l.id from SeatMapLayout l
            where l.venue.id = :venueId and l.status = com.seatswap.domain.SeatMapStatus.DRAFT
              and l.zoneName = :zoneName
            """)
    Optional<Long> findDraftIdByZone(@Param("venueId") Long venueId, @Param("zoneName") String zoneName);

    /** 구역이 없는(null) DRAFT의 id. */
    @Query("""
            select l.id from SeatMapLayout l
            where l.venue.id = :venueId and l.status = com.seatswap.domain.SeatMapStatus.DRAFT
              and l.zoneName is null
            """)
    Optional<Long> findDraftIdWithoutZone(@Param("venueId") Long venueId);

    /** 공연장의 좌석표 목록 (seat_json 미포함). */
    @Query("""
            select new com.seatswap.dto.response.SeatMapSummaryResponse(l.id, l.zoneName, l.status, l.version, l.createdAt)
            from SeatMapLayout l
            where l.venue.id = :venueId
            order by l.id asc
            """)
    List<SeatMapSummaryResponse> findSummariesByVenueId(@Param("venueId") Long venueId);

    // TODO: 임시 기능
    // ---- TEMP-DRAFT-DELETE: 테스트용 임시 삭제 (추후 제거 또는 비활성화) ----

    /** 상태만 읽는다 (seat_json LOB을 로딩하지 않기 위해). */
    @Query("select l.status from SeatMapLayout l where l.id = :id")
    Optional<SeatMapStatus> findStatusById(@Param("id") Long id);

    /** DRAFT만 지운다. 지운 행 수(0이면 이미 없거나 DRAFT가 아님). */
    @Modifying
    @Query("delete from SeatMapLayout l where l.id = :id and l.status = com.seatswap.domain.SeatMapStatus.DRAFT")
    int deleteDraftById(@Param("id") Long id);
}
