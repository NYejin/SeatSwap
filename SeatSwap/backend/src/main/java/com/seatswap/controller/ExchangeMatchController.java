package com.seatswap.controller;

import com.seatswap.dto.request.ProposalRequest;
import com.seatswap.dto.response.ExchangeMatchResponse;
import com.seatswap.dto.response.PageResponse;
import com.seatswap.security.AuthUserPrincipal;
import com.seatswap.service.ExchangeMatchQueryService;
import com.seatswap.service.ExchangeMatchService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 매칭 생성·예약·예약 취소·거절·취소 API (FR-04 교환 흐름: 후보 선택 -> 채팅 -> 예약). 모두 로그인 필요.
 * 상태 전이 표와 오류 코드는 {@link ExchangeMatchService} Javadoc 참고. 교환 완료(COMPLETED)·채팅·이력은 후속이다.
 */
@RestController
@RequestMapping("/api/exchange")
@RequiredArgsConstructor
public class ExchangeMatchController {

    private final ExchangeMatchService matchService;
    private final ExchangeMatchQueryService queryService;

    /**
     * 내 매칭 목록 (보낸 SENT = 내가 제안자 a측, 받은 RECEIVED = b측, 기본 ALL). status 는 반복 또는 쉼표로 여러 개 가능.
     * updated_at 내림차순. size 기본 20, 최대 100. 잘못된 role·status·page 는 400.
     */
    @GetMapping("/matches/me")
    public PageResponse<ExchangeMatchResponse> mine(@RequestParam(required = false) String role,
                                                    @RequestParam(required = false) List<String> status,
                                                    @RequestParam(defaultValue = "0") int page,
                                                    @RequestParam(defaultValue = "20") int size,
                                                    @AuthenticationPrincipal AuthUserPrincipal principal) {
        return queryService.findMine(principal.userId(), role, status, page, size);
    }

    /** 매칭 단건 (같은 모양). 비참여자·없는 매칭은 404. */
    @GetMapping("/matches/{id}")
    public ExchangeMatchResponse get(@PathVariable Long id, @AuthenticationPrincipal AuthUserPrincipal principal) {
        return queryService.findOne(principal.userId(), id);
    }

    /** 후보를 골라 매칭(CHATTING) 시작 -> 201. 남의 요청 403, 없는 요청 404, 같은 쌍 열린 매칭 409, 조건 불충족·잠금·마감 422. */
    @PostMapping("/requests/{id}/proposals")
    public ResponseEntity<ExchangeMatchResponse> propose(@PathVariable Long id,
                                                         @Valid @RequestBody ProposalRequest request,
                                                         @AuthenticationPrincipal AuthUserPrincipal principal) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(matchService.propose(principal.userId(), id, request.targetRequestId()));
    }

    /** 예약 -> 200. 둘 중 한 명이 누르면 RESERVED(두 티켓 잠금). 이미 RESERVED 면 누가 눌렀든 멱등 200. */
    @PostMapping("/matches/{id}/reserve")
    public ExchangeMatchResponse reserve(@PathVariable Long id, @AuthenticationPrincipal AuthUserPrincipal principal) {
        return matchService.reserve(principal.userId(), id);
    }

    /** 예약 취소 -> 200 + CHATTING 복귀(잠금 해제, 수락 표시 초기화). 두 참여자 누구나, CHATTING 이면 멱등 200. */
    @PostMapping("/matches/{id}/unreserve")
    public ExchangeMatchResponse unreserve(@PathVariable Long id, @AuthenticationPrincipal AuthUserPrincipal principal) {
        return matchService.unreserve(principal.userId(), id);
    }

    /** 제안받은 쪽의 거절 -> 200 + CANCELED. 제안한 쪽은 403(취소를 사용). RESERVED 에서는 409(먼저 예약 취소). */
    @PostMapping("/matches/{id}/reject")
    public ExchangeMatchResponse reject(@PathVariable Long id, @AuthenticationPrincipal AuthUserPrincipal principal) {
        return matchService.reject(principal.userId(), id);
    }

    /** 참여자 누구나 취소(채팅 종료) -> 200 + CANCELED. RESERVED 에서는 409(먼저 예약 취소). */
    @PostMapping("/matches/{id}/cancel")
    public ExchangeMatchResponse cancel(@PathVariable Long id, @AuthenticationPrincipal AuthUserPrincipal principal) {
        return matchService.cancel(principal.userId(), id);
    }
}
