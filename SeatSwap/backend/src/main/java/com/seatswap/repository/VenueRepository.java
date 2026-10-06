package com.seatswap.repository;

import com.seatswap.domain.Venue;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface VenueRepository extends JpaRepository<Venue, Long> {

    Optional<Venue> findByNormalizedName(String normalizedName);

    /**
     * 원문 이름 또는 정규화 이름 LIKE 검색. 패턴은 호출 측에서 '!'로 이스케이프한 "%...%" 형태로 넘긴다
     * (MySQL은 문자열 리터럴에서 백슬래시를 이스케이프로 해석하므로 ESCAPE 문자로 '!'를 쓴다).
     * 대소문자 무시는 컬럼 collation(utf8mb4_0900_ai_ci)에 맡긴다 - lower()를 씌우지 않는다.
     */
    @Query("""
            select v from Venue v
            where v.name like :namePattern escape '!'
               or v.normalizedName like :normalizedPattern escape '!'
            order by v.name asc, v.id asc
            """)
    List<Venue> search(@Param("namePattern") String namePattern,
                       @Param("normalizedPattern") String normalizedPattern,
                       Pageable pageable);
}
