package com.seatswap.service;

import com.seatswap.domain.Venue;
import com.seatswap.dto.request.VenueCreateRequest;
import com.seatswap.dto.response.VenueResponse;
import com.seatswap.exception.FieldValidationException;
import com.seatswap.repository.VenueRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

/** 공연장 검색·추가 (FR-02). 공연장 수정·삭제는 제공하지 않는다 (공유 기준 데이터). */
@Slf4j
@Service
@RequiredArgsConstructor
public class VenueService {

    static final int SEARCH_LIMIT = 20;
    static final int QUERY_MAX_LENGTH = 100;
    static final String UNIQUE_NORMALIZED_NAME = "uk_venue_normalized_name";

    private final VenueRepository venueRepository;

    /**
     * 원문 이름(대소문자 무시) 또는 정규화 이름 부분 일치, 최대 20건, 이름순.
     * 검색어가 비어 있으면 이름순 앞 20건. 결과가 없으면 빈 목록.
     */
    @Transactional(readOnly = true)
    public List<VenueResponse> search(String query) {
        String q = Venue.cleanDisplayText(query);
        if (q != null && q.length() > QUERY_MAX_LENGTH) {
            throw new FieldValidationException("query", "검색어는 100자 이하로 입력해주세요.");
        }
        if (q == null || q.isEmpty()) {
            return venueRepository.findAll(PageRequest.of(0, SEARCH_LIMIT, Sort.by("name", "id")))
                    .map(VenueResponse::from)
                    .getContent();
        }
        String namePattern = LikePatterns.contains(q);
        String normalized = Venue.normalizeName(q);
        // 정규화하면 빈 문자열이 되는 검색어(구두점만 등)는 원문 패턴으로만 찾는다
        String normalizedPattern = normalized.isEmpty() ? namePattern : LikePatterns.contains(normalized);
        return venueRepository.search(namePattern, normalizedPattern, PageRequest.of(0, SEARCH_LIMIT)).stream()
                .map(VenueResponse::from)
                .toList();
    }

    /**
     * 정규화 이름이 같은 공연장이 있으면 그것을 돌려주고(created=false), 없으면 만든다(created=true).
     * 동시 생성 레이스: saveAndFlush에서 uk_venue_normalized_name 위반이 나면 이긴 쪽 레코드를 재조회해 돌려준다.
     * 메서드 자체에 트랜잭션을 걸지 않는다 — 실패한 insert 트랜잭션은 리포지토리 단위로 롤백되고,
     * 재조회는 새 트랜잭션에서 수행해야 하기 때문(실패한 영속성 컨텍스트 재사용 금지).
     */
    public VenueCreateResult create(VenueCreateRequest request) {
        String name = Venue.cleanDisplayText(request.name());
        if (name == null || name.isEmpty()) {
            throw new FieldValidationException("name", "공연장 이름을 입력해주세요.");
        }
        if (name.length() > Venue.NAME_MAX_LENGTH) {
            throw new FieldValidationException("name", "공연장 이름은 100자 이하로 입력해주세요.");
        }
        String normalized = Venue.normalizeName(name);
        if (normalized.isEmpty() || normalized.length() > Venue.NAME_MAX_LENGTH) {
            throw new FieldValidationException("name", "공연장 이름에 글자나 숫자가 있어야 합니다.");
        }
        String address = Venue.cleanDisplayText(request.address());
        if (address != null && address.length() > Venue.ADDRESS_MAX_LENGTH) {
            throw new FieldValidationException("address", "주소는 255자 이하로 입력해주세요.");
        }

        Optional<Venue> existing = venueRepository.findByNormalizedName(normalized);
        if (existing.isPresent()) {
            return new VenueCreateResult(VenueResponse.from(existing.get()), false);
        }
        try {
            Venue saved = venueRepository.saveAndFlush(Venue.create(name, address));
            return new VenueCreateResult(VenueResponse.from(saved), true);
        } catch (DataIntegrityViolationException e) {
            if (!DataIntegrityViolations.isViolationOf(e, UNIQUE_NORMALIZED_NAME)) {
                throw e;
            }
            log.info("Venue create race on normalized name '{}' — returning existing", normalized);
            return venueRepository.findByNormalizedName(normalized)
                    .map(v -> new VenueCreateResult(VenueResponse.from(v), false))
                    .orElseThrow(() -> e);
        }
    }

    public record VenueCreateResult(VenueResponse venue, boolean created) {}
}
