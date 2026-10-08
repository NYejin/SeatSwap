package com.seatswap.controller;

import com.seatswap.dto.request.ExchangeRequestCreateRequest;
import com.seatswap.dto.request.ExchangeRequestUpdateRequest;
import com.seatswap.dto.response.ExchangeRequestResponse;
import com.seatswap.security.AuthUserPrincipal;
import com.seatswap.service.ExchangeRequestService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
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

import java.util.List;

/** 교환 희망 조건 API (FR-04 교환 요청): 등록·내 목록·수정·삭제. 모두 로그인 필요, 본인 티켓·요청만 다룬다. */
@RestController
@RequestMapping("/api/exchange/requests")
@RequiredArgsConstructor
public class ExchangeRequestController {

    private final ExchangeRequestService exchangeRequestService;

    /** 201 + 등록된 요청. 티켓당 1개(중복 409), 남의 티켓 403, 없는 티켓 404, 내린 티켓·내 좌석 포함·상한 초과 422. */
    @PostMapping
    public ResponseEntity<ExchangeRequestResponse> create(@Valid @RequestBody ExchangeRequestCreateRequest request,
                                                          @AuthenticationPrincipal AuthUserPrincipal principal) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(exchangeRequestService.create(principal.userId(), request));
    }

    /** 내 요청 목록(요청 id 오름차순). ticketId 를 주면 그 티켓의 요청만. 펼친 좌석은 개수만 담는다. */
    @GetMapping("/me")
    public List<ExchangeRequestResponse> mine(@RequestParam(required = false) Long ticketId,
                                              @AuthenticationPrincipal AuthUserPrincipal principal) {
        return exchangeRequestService.listMine(principal.userId(), ticketId);
    }

    /** 범위·희망 회차·추가금 전체 교체 -> 200 + 갱신된 요청. 남의 요청 403, 없는 요청 404. */
    @PatchMapping("/{id}")
    public ExchangeRequestResponse update(@PathVariable Long id,
                                          @Valid @RequestBody ExchangeRequestUpdateRequest request,
                                          @AuthenticationPrincipal AuthUserPrincipal principal) {
        return exchangeRequestService.update(principal.userId(), id, request);
    }

    /** 하드 삭제 -> 204. 남의 요청 403, 없는 요청 404. */
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable Long id,
                                       @AuthenticationPrincipal AuthUserPrincipal principal) {
        exchangeRequestService.delete(principal.userId(), id);
        return ResponseEntity.noContent().build();
    }
}
