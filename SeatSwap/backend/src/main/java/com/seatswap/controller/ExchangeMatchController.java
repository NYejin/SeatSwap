package com.seatswap.controller;

import com.seatswap.dto.request.ProposalRequest;
import com.seatswap.dto.response.ExchangeMatchResponse;
import com.seatswap.security.AuthUserPrincipal;
import com.seatswap.service.ExchangeMatchService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 매칭 생성·예약(수락)·거절·취소 API (FR-04 교환 흐름: 후보 선택 -> 채팅 -> 예약). 모두 로그인 필요.
 * 상태 전이 표와 오류 코드는 {@link ExchangeMatchService} Javadoc 참고. 교환 완료(COMPLETED)·채팅·이력은 후속이다.
 */
@RestController
@RequestMapping("/api/exchange")
@RequiredArgsConstructor
public class ExchangeMatchController {

    private final ExchangeMatchService matchService;

    /** 후보를 골라 매칭(CHATTING) 시작 -> 201. 남의 요청 403, 없는 요청 404, 같은 쌍 열린 매칭 409, 조건 불충족·잠금·마감 422. */
    @PostMapping("/requests/{id}/proposals")
    public ResponseEntity<ExchangeMatchResponse> propose(@PathVariable Long id,
                                                         @Valid @RequestBody ProposalRequest request,
                                                         @AuthenticationPrincipal AuthUserPrincipal principal) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(matchService.propose(principal.userId(), id, request.targetRequestId()));
    }

    /** 내 쪽 예약 동의('이 사람과 교환할게요') -> 200. 양쪽이 누르면 RESERVED(두 티켓 잠금). 이미 눌렀다면 멱등 200. */
    @PostMapping("/matches/{id}/accept")
    public ExchangeMatchResponse accept(@PathVariable Long id, @AuthenticationPrincipal AuthUserPrincipal principal) {
        return matchService.accept(principal.userId(), id);
    }

    /** 제안받은 쪽의 거절 -> 200 + CANCELED. 제안한 쪽은 403(취소를 사용). */
    @PostMapping("/matches/{id}/reject")
    public ExchangeMatchResponse reject(@PathVariable Long id, @AuthenticationPrincipal AuthUserPrincipal principal) {
        return matchService.reject(principal.userId(), id);
    }

    /** 참여자 누구나 양쪽 완료 전 취소 -> 200 + CANCELED (RESERVED 였다면 잠금 해제). */
    @PostMapping("/matches/{id}/cancel")
    public ExchangeMatchResponse cancel(@PathVariable Long id, @AuthenticationPrincipal AuthUserPrincipal principal) {
        return matchService.cancel(principal.userId(), id);
    }
}
