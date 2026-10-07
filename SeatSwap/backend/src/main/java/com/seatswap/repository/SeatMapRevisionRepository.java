package com.seatswap.repository;

import com.seatswap.domain.SeatMapRevision;
import com.seatswap.domain.SeatMapRevisionAction;
import com.seatswap.dto.response.SeatMapRevisionSummaryResponse;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.Optional;

public interface SeatMapRevisionRepository extends JpaRepository<SeatMapRevision, Long> {

    /** 좌석표의 수정 로그 목록 (최신 revision 먼저, 변경 항목 수 포함, before/after JSON은 읽지 않는다). */
    @Query(value = """
            select new com.seatswap.dto.response.SeatMapRevisionSummaryResponse(
                r.id, r.revisionNo, r.actionType, r.layoutStatus, r.actor.id, r.reason, r.createdAt,
                (select count(i) from SeatMapRevisionItem i where i.revision = r))
            from SeatMapRevision r
            where r.seatMapLayout.id = :seatMapId
            order by r.revisionNo desc
            """,
            countQuery = "select count(r) from SeatMapRevision r where r.seatMapLayout.id = :seatMapId")
    Page<SeatMapRevisionSummaryResponse> findSummariesBySeatMapId(@Param("seatMapId") Long seatMapId, Pageable pageable);

    /** 다른 좌석표의 로그를 id로 조회하지 못하도록 좌석표까지 함께 맞춘다. */
    Optional<SeatMapRevision> findByIdAndSeatMapLayout_Id(Long id, Long seatMapId);

    /** 사용자가 since 이후(초과)에 낸 해당 종류의 수정 수 — 일일 수정 요청 한도 (idx_seat_map_revision_actor_created). */
    long countByActor_IdAndActionTypeAndCreatedAtAfter(Long actorId, SeatMapRevisionAction actionType,
                                                       LocalDateTime since);

    /** RECOGNIZED 이외(수정·정정 반영 등)의 로그 수 — DRAFT 삭제 가능 여부 판정. */
    long countBySeatMapLayout_IdAndActionTypeNot(Long seatMapId, SeatMapRevisionAction actionType);

    /**
     * DRAFT 삭제 시 최초 인식 로그(항목 없음)만 정리한다. 수정 이력이 있는 좌석표는 서비스가 먼저 삭제를 거부한다.
     * @Immutable 엔티티에 대한 HQL 벌크 삭제를 피하려고 native로 지운다.
     */
    @Modifying
    @Query(value = "delete from seat_map_revision where seatmap_id = :seatMapId", nativeQuery = true)
    int deleteAllBySeatMapId(@Param("seatMapId") Long seatMapId);
}
