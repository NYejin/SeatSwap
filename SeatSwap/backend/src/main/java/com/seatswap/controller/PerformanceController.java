package com.seatswap.controller;

import com.seatswap.dto.request.PerformanceCreateRequest;
import com.seatswap.dto.request.PerformanceUpdateRequest;
import com.seatswap.dto.request.SessionRequest;
import com.seatswap.dto.response.PageResponse;
import com.seatswap.dto.response.PerformanceDetailResponse;
import com.seatswap.dto.response.PerformanceLookupResponse;
import com.seatswap.dto.response.PerformanceSummaryResponse;
import com.seatswap.dto.response.SessionResponse;
import com.seatswap.security.AuthUserPrincipal;
import com.seatswap.service.PerformanceService;
import com.seatswap.service.PerformanceSessionService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;

/**
 * 공연·회차 API (FR-02). 모두 로그인 필요 (SecurityConfig anyRequest().authenticated()).
 * 수정·삭제 권한(등록자) 위반은 서비스에서 AccessDeniedException → 403 {"message":"접근 권한이 없습니다."}.
 */
@RestController
@RequestMapping("/api/performances")
@RequiredArgsConstructor
public class PerformanceController {

    private final PerformanceService performanceService;
    private final PerformanceSessionService sessionService;

    /** 목록. asOf("yyyy-MM-ddTHH:mm", 선택): 페이지를 넘기는 동안 정렬 기준 시각 고정. 형식 오류는 400. */
    @GetMapping
    public PageResponse<PerformanceSummaryResponse> search(
            @RequestParam(required = false) String query,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) @DateTimeFormat(pattern = "yyyy-MM-dd'T'HH:mm") LocalDateTime asOf) {
        return performanceService.search(query, page, size, asOf);
    }

    /** 링크로 기존 공연 조회 — {exists, performanceId|null}. */
    @GetMapping("/lookup")
    public PerformanceLookupResponse lookup(@RequestParam String sourceUrl) {
        return performanceService.lookup(sourceUrl);
    }

    @GetMapping("/{id}")
    public PerformanceDetailResponse get(@PathVariable Long id,
                                         @AuthenticationPrincipal AuthUserPrincipal principal) {
        return performanceService.get(id, principal.userId());
    }

    /** 201 + 상세. 같은 링크의 공연이 있으면 409 {"message":"이미 등록된 공연입니다.","performanceId":N}. */
    @PostMapping
    public ResponseEntity<PerformanceDetailResponse> create(@Valid @RequestBody PerformanceCreateRequest request,
                                                            @AuthenticationPrincipal AuthUserPrincipal principal) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(performanceService.create(principal.userId(), request));
    }

    @PatchMapping("/{id}")
    public PerformanceDetailResponse update(@PathVariable Long id,
                                            @Valid @RequestBody PerformanceUpdateRequest request,
                                            @AuthenticationPrincipal AuthUserPrincipal principal) {
        return performanceService.update(id, principal.userId(), request);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable Long id,
                                       @AuthenticationPrincipal AuthUserPrincipal principal) {
        performanceService.delete(id, principal.userId());
        return ResponseEntity.noContent().build();
    }

    /** 회차 추가 (로그인 사용자 누구나). 201 {id, startsAt}. 같은 시각이면 409. */
    @PostMapping("/{id}/sessions")
    public ResponseEntity<SessionResponse> addSession(@PathVariable Long id,
                                                      @Valid @RequestBody SessionRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(sessionService.add(id, request.startsAt()));
    }

    /** 회차 일시 변경 (공연 등록자만, 티켓 0건일 때만). 200 {id, startsAt}. */
    @PatchMapping("/{id}/sessions/{sessionId}")
    public SessionResponse rescheduleSession(@PathVariable Long id,
                                                       @PathVariable Long sessionId,
                                                       @Valid @RequestBody SessionRequest request,
                                                       @AuthenticationPrincipal AuthUserPrincipal principal) {
        return sessionService.reschedule(id, sessionId, principal.userId(), request.startsAt());
    }

    @DeleteMapping("/{id}/sessions/{sessionId}")
    public ResponseEntity<Void> deleteSession(@PathVariable Long id,
                                              @PathVariable Long sessionId,
                                              @AuthenticationPrincipal AuthUserPrincipal principal) {
        sessionService.delete(id, sessionId, principal.userId());
        return ResponseEntity.noContent().build();
    }
}
