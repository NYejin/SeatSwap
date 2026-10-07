package com.seatswap.controller;

import com.seatswap.dto.response.SeatMapResponse;
import com.seatswap.dto.response.SeatMapSummaryResponse;
import com.seatswap.security.AuthUserPrincipal;
import com.seatswap.service.SeatMapService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

/** 좌석표 등록(DRAFT)·조회 (UC-03 일부). 로그인 필요. 오류 신고(corrections)는 아직 없다. */
@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class SeatMapController {
    private final SeatMapService seatMapService;

    /** 이미지 업로드 -> 인식 -> DRAFT 좌석표 저장 (이미지는 저장하지 않는다). */
    @PostMapping(value = "/venues/{venueId}/seatmaps", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    public SeatMapResponse createDraft(@PathVariable Long venueId,
                                       @AuthenticationPrincipal AuthUserPrincipal principal,
                                       @RequestPart("file") MultipartFile file,
                                       @RequestParam(required = false) String zoneName,
                                       @RequestParam(required = false) String aisleMode) {
        return seatMapService.createDraft(venueId, principal.userId(), file, zoneName, aisleMode);
    }

    @GetMapping("/venues/{venueId}/seatmaps")
    public List<SeatMapSummaryResponse> listByVenue(@PathVariable Long venueId) {
        return seatMapService.listByVenue(venueId);
    }

    @GetMapping("/seatmaps/{id}")
    public SeatMapResponse get(@PathVariable Long id) {
        return seatMapService.get(id);
    }

    // TODO: 임시 기능
    // TEMP(테스트용): 추후 제거 또는 비활성화 — TEMP-DRAFT-DELETE
    // seatmap-service.dev-draft-delete-enabled=false 이면 서비스가 404로 응답한다.
    @DeleteMapping("/seatmaps/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteDraft(@PathVariable Long id, @AuthenticationPrincipal AuthUserPrincipal principal) {
        seatMapService.deleteDraft(id, principal.userId());
    }

    // TODO: POST /seatmaps/{id}/corrections (오류 신고)
}
