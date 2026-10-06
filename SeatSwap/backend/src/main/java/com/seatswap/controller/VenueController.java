package com.seatswap.controller;

import com.seatswap.dto.request.VenueCreateRequest;
import com.seatswap.dto.response.VenueResponse;
import com.seatswap.service.VenueService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** 공연장 검색·추가 (FR-02). 로그인 필요. 수정·삭제 API는 없다. */
@RestController
@RequestMapping("/api/venues")
@RequiredArgsConstructor
public class VenueController {

    private final VenueService venueService;

    /** 이름 검색 (최대 20건, 없으면 빈 배열). */
    @GetMapping
    public List<VenueResponse> search(@RequestParam(required = false) String query) {
        return venueService.search(query);
    }

    /** 정규화 이름이 같은 공연장이 있으면 200 + 기존 공연장, 없으면 201 + 새 공연장. */
    @PostMapping
    public ResponseEntity<VenueResponse> create(@Valid @RequestBody VenueCreateRequest request) {
        VenueService.VenueCreateResult result = venueService.create(request);
        return ResponseEntity.status(result.created() ? HttpStatus.CREATED : HttpStatus.OK).body(result.venue());
    }
}
