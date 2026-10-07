package com.seatswap.repository;

import com.seatswap.domain.SeatMapRevisionItem;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface SeatMapRevisionItemRepository extends JpaRepository<SeatMapRevisionItem, Long> {

    /** 한 수정 로그의 변경 항목 (입력 순서 = id 순). */
    List<SeatMapRevisionItem> findByRevision_IdOrderByIdAsc(Long revisionId);
}
