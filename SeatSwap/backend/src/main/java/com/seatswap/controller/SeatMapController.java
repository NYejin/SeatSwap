package com.seatswap.controller;

import com.seatswap.dto.request.CorrectionRequest;
import com.seatswap.dto.request.SeatEditRequest;
import com.seatswap.dto.response.CorrectionResponse;
import com.seatswap.dto.response.PageResponse;
import com.seatswap.dto.response.SeatMapResponse;
import com.seatswap.dto.response.SeatMapRevisionDetailResponse;
import com.seatswap.dto.response.SeatMapRevisionSummaryResponse;
import com.seatswap.dto.response.SeatMapSummaryResponse;
import com.seatswap.security.AuthUserPrincipal;
import com.seatswap.service.SeatMapEditService;
import com.seatswap.service.SeatMapService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

/** 좌석표 등록(DRAFT)·조회·수정·정정 신고·수정 로그 조회 (UC-03). 모두 로그인 필요, 역할은 DB role(principal.isAdmin()). */
@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class SeatMapController {
    private final SeatMapService seatMapService;
    private final SeatMapEditService seatMapEditService;

    /** 이미지 업로드 -> 인식 -> DRAFT 좌석표 저장 (이미지는 저장하지 않는다). */
    @PostMapping(value = "/venues/{venueId}/seatmaps", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    public SeatMapResponse createDraft(@PathVariable Long venueId,
                                       @AuthenticationPrincipal AuthUserPrincipal principal,
                                       @RequestPart("file") MultipartFile file,
                                       @RequestParam(required = false) String zoneName,
                                       @RequestParam(required = false) String aisleMode) {
        return seatMapService.createDraft(venueId, principal.userId(), principal.isAdmin(), file, zoneName, aisleMode);
    }

    @GetMapping("/venues/{venueId}/seatmaps")
    public List<SeatMapSummaryResponse> listByVenue(@PathVariable Long venueId) {
        return seatMapService.listByVenue(venueId);
    }

    @GetMapping("/seatmaps/{id}")
    public SeatMapResponse get(@PathVariable Long id, @AuthenticationPrincipal AuthUserPrincipal principal) {
        return seatMapService.get(id, principal.userId(), principal.isAdmin());
    }

    // 작성자 또는 ADMIN만 삭제 (아니면 403). TEMP-DRAFT-DELETE 플래그가 true인 동안은 로그인한 누구나 (테스트용, 추후 제거).
    @DeleteMapping("/seatmaps/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteDraft(@PathVariable Long id, @AuthenticationPrincipal AuthUserPrincipal principal) {
        seatMapService.deleteDraft(id, principal.userId(), principal.isAdmin());
    }

    /** 좌석 라벨(행/열 번호) 수정. DRAFT는 누구나 즉시 반영, OFFICIAL은 ADMIN만 (일반 사용자 403). */
    @PatchMapping("/seatmaps/{id}/seats")
    public SeatMapResponse editSeats(@PathVariable Long id,
                                     @AuthenticationPrincipal AuthUserPrincipal principal,
                                     @Valid @RequestBody SeatEditRequest request) {
        return seatMapEditService.editSeats(id, principal.userId(), principal.isAdmin(), request);
    }

    /** 정식(OFFICIAL) 좌석표 정정 신고. 같은 정정의 대기 신고가 서로 다른 신고자 2명 이상이면 자동 반영. body의 note는 현재 저장하지 않고 무시한다. */
    @PostMapping("/seatmaps/{id}/corrections")
    @ResponseStatus(HttpStatus.CREATED)
    public CorrectionResponse reportCorrection(@PathVariable Long id,
                                               @AuthenticationPrincipal AuthUserPrincipal principal,
                                               @Valid @RequestBody CorrectionRequest request) {
        return seatMapEditService.reportCorrection(id, principal.userId(), principal.isAdmin(), request);
    }

    /** 수정 로그 목록 (ADMIN만). */
    @GetMapping("/seatmaps/{id}/revisions")
    public PageResponse<SeatMapRevisionSummaryResponse> listRevisions(@PathVariable Long id,
                                                                      @AuthenticationPrincipal AuthUserPrincipal principal,
                                                                      @RequestParam(defaultValue = "0") int page,
                                                                      @RequestParam(defaultValue = "20") int size) {
        return seatMapEditService.listRevisions(id, principal.isAdmin(), page, size);
    }

    /** 수정 로그 상세 (ADMIN만). */
    @GetMapping("/seatmaps/{id}/revisions/{revisionId}")
    public SeatMapRevisionDetailResponse getRevision(@PathVariable Long id, @PathVariable Long revisionId,
                                                     @AuthenticationPrincipal AuthUserPrincipal principal) {
        return seatMapEditService.getRevision(id, revisionId, principal.isAdmin());
    }
}
