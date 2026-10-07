package com.seatswap.controller;

import com.seatswap.config.SecurityConfig;
import com.seatswap.domain.CorrectionStatus;
import com.seatswap.domain.SeatMapRevisionAction;
import com.seatswap.domain.SeatMapStatus;
import com.seatswap.domain.UserRole;
import com.seatswap.dto.request.CorrectionRequest;
import com.seatswap.dto.request.SeatEditRequest;
import com.seatswap.dto.response.CorrectionResponse;
import com.seatswap.dto.response.PageResponse;
import com.seatswap.dto.response.SeatMapResponse;
import com.seatswap.dto.response.SeatMapSeat;
import com.seatswap.dto.response.SeatMapRevisionDetailResponse;
import com.seatswap.dto.response.SeatMapRevisionItemResponse;
import com.seatswap.dto.response.SeatMapRevisionSummaryResponse;
import com.seatswap.domain.SeatMapField;
import com.seatswap.exception.ConflictException;
import com.seatswap.exception.ForbiddenException;
import com.seatswap.exception.SeatMapException;
import com.seatswap.security.AuthUserPrincipal;
import com.seatswap.security.CustomUserDetailsService;
import com.seatswap.security.JwtAccessDeniedHandler;
import com.seatswap.security.JwtAuthenticationEntryPoint;
import com.seatswap.security.JwtTokenProvider;
import com.seatswap.security.SecurityErrorResponseWriter;
import com.seatswap.service.SeatMapEditService;
import com.seatswap.service.SeatMapService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 좌석표 수정·정정 신고·수정 로그 조회 엔드포인트: 인증, 요청 검증, 오류 응답 형식, 역할 전달. */
@WebMvcTest(controllers = SeatMapController.class)
@Import({SecurityConfig.class, JwtAuthenticationEntryPoint.class, JwtAccessDeniedHandler.class,
        SecurityErrorResponseWriter.class})
class SeatMapEditControllerSliceTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private JwtTokenProvider tokenProvider;
    @MockBean
    private CustomUserDetailsService userDetailsService;
    @MockBean
    private SeatMapService seatMapService;
    @MockBean
    private SeatMapEditService editService;

    private static final String USER = "Bearer user";
    private static final String ADMIN = "Bearer admin";
    private static final String VALID_EDIT = """
            {"expectedVersion":1,"reason":"실제와 달라요","changes":[{"uid":"s0003","field":"ROW_LABEL","value":3}]}""";

    @BeforeEach
    void setUp() {
        when(tokenProvider.validateToken("user")).thenReturn(true);
        when(tokenProvider.isRefreshToken("user")).thenReturn(false);
        when(tokenProvider.getUserId("user")).thenReturn(1L);
        when(userDetailsService.loadUserById(1L)).thenReturn(AuthUserPrincipal.of(1L, "u@b.com", UserRole.USER));
        when(tokenProvider.validateToken("admin")).thenReturn(true);
        when(tokenProvider.isRefreshToken("admin")).thenReturn(false);
        when(tokenProvider.getUserId("admin")).thenReturn(2L);
        when(userDetailsService.loadUserById(2L)).thenReturn(AuthUserPrincipal.of(2L, "a@b.com", UserRole.ADMIN));
    }

    private static SeatMapResponse response() {
        return new SeatMapResponse(55L, 10L, "KSPO DOME", "A구역", SeatMapStatus.DRAFT, 2, 700, 400,
                List.of(new SeatMapSeat("s0003", 3, 1, 70, 60, 18, 18)),
                LocalDateTime.of(2026, 10, 7, 12, 0, 1), LocalDateTime.of(2026, 10, 7, 12, 5, 1), true);
    }

    @Test
    void allEditEndpointsRequireLogin() throws Exception {
        mockMvc.perform(patch("/api/seatmaps/55/seats").contentType(MediaType.APPLICATION_JSON).content(VALID_EDIT))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(post("/api/seatmaps/55/corrections").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"uid\":\"s0003\",\"field\":\"ROW_LABEL\",\"value\":3}"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/seatmaps/55/revisions")).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/seatmaps/55/revisions/1")).andExpect(status().isUnauthorized());
        verifyNoInteractions(editService);
    }

    @Test
    void editReturnsSeatMapAndPassesRoleFromPrincipal() throws Exception {
        when(editService.editSeats(eq(55L), eq(1L), eq(false), any(SeatEditRequest.class))).thenReturn(response());

        mockMvc.perform(patch("/api/seatmaps/55/seats").header("Authorization", USER)
                        .contentType(MediaType.APPLICATION_JSON).content(VALID_EDIT))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(2))
                .andExpect(jsonPath("$.canDelete").value(true))
                .andExpect(jsonPath("$.seats[0].row").value(3));

        ArgumentCaptor<SeatEditRequest> captor = ArgumentCaptor.forClass(SeatEditRequest.class);
        verify(editService).editSeats(eq(55L), eq(1L), eq(false), captor.capture());
        assertThat(captor.getValue().expectedVersion()).isEqualTo(1);
        assertThat(captor.getValue().changes().get(0).field()).isEqualTo(SeatMapField.ROW_LABEL);

        when(editService.editSeats(eq(55L), eq(2L), eq(true), any(SeatEditRequest.class))).thenReturn(response());
        mockMvc.perform(patch("/api/seatmaps/55/seats").header("Authorization", ADMIN)
                        .contentType(MediaType.APPLICATION_JSON).content(VALID_EDIT))
                .andExpect(status().isOk());
    }

    @Test
    void editRequestValidationIs400() throws Exception {
        String noReason = "{\"expectedVersion\":1,\"reason\":\"  \",\"changes\":[{\"uid\":\"s1\",\"field\":\"ROW_LABEL\",\"value\":3}]}";
        String noVersion = "{\"reason\":\"r\",\"changes\":[{\"uid\":\"s1\",\"field\":\"ROW_LABEL\",\"value\":3}]}";
        String emptyChanges = "{\"expectedVersion\":1,\"reason\":\"r\",\"changes\":[]}";
        String zeroValue = "{\"expectedVersion\":1,\"reason\":\"r\",\"changes\":[{\"uid\":\"s1\",\"field\":\"ROW_LABEL\",\"value\":0}]}";
        String hugeValue = "{\"expectedVersion\":1,\"reason\":\"r\",\"changes\":[{\"uid\":\"s1\",\"field\":\"COL_LABEL\",\"value\":10000}]}";
        String badField = "{\"expectedVersion\":1,\"reason\":\"r\",\"changes\":[{\"uid\":\"s1\",\"field\":\"WIDTH\",\"value\":3}]}";
        String longReason = "{\"expectedVersion\":1,\"reason\":\"" + "가".repeat(501)
                + "\",\"changes\":[{\"uid\":\"s1\",\"field\":\"ROW_LABEL\",\"value\":3}]}";
        StringBuilder many = new StringBuilder("{\"expectedVersion\":1,\"reason\":\"r\",\"changes\":[");
        for (int i = 0; i < 2001; i++) {
            many.append(i == 0 ? "" : ",").append("{\"uid\":\"s").append(i).append("\",\"field\":\"ROW_LABEL\",\"value\":1}");
        }
        many.append("]}");

        String zeroVersion = "{\"expectedVersion\":0,\"reason\":\"r\",\"changes\":[{\"uid\":\"s1\",\"field\":\"ROW_LABEL\",\"value\":3}]}";
        String nullChange = "{\"expectedVersion\":1,\"reason\":\"r\",\"changes\":[null]}";
        for (String body : List.of(zeroVersion, nullChange, noReason, noVersion, emptyChanges, zeroValue, hugeValue, badField, longReason,
                many.toString())) {
            mockMvc.perform(patch("/api/seatmaps/55/seats").header("Authorization", USER)
                            .contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isBadRequest());
        }
        verify(editService, never()).editSeats(any(), any(), any(Boolean.class), any());
    }

    @Test
    void editErrorsRenderDocumentedBodies() throws Exception {
        when(editService.editSeats(eq(55L), eq(1L), eq(false), any()))
                .thenThrow(new ForbiddenException("정식 좌석표는 정정 신고로만 수정할 수 있어요."))
                .thenThrow(new SeatMapException(HttpStatus.CONFLICT, "VERSION_CONFLICT", "충돌"))
                .thenThrow(new SeatMapException(HttpStatus.UNPROCESSABLE_ENTITY, "UNKNOWN_SEAT", "없는 좌석"))
                .thenThrow(new SeatMapException(HttpStatus.UNPROCESSABLE_ENTITY, "DUPLICATE_SEAT_NUMBER", "겹침"));

        mockMvc.perform(patch("/api/seatmaps/55/seats").header("Authorization", USER)
                        .contentType(MediaType.APPLICATION_JSON).content(VALID_EDIT))
                .andExpect(status().isForbidden())
                .andExpect(content().json("{\"message\":\"정식 좌석표는 정정 신고로만 수정할 수 있어요.\"}", true));
        mockMvc.perform(patch("/api/seatmaps/55/seats").header("Authorization", USER)
                        .contentType(MediaType.APPLICATION_JSON).content(VALID_EDIT))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("VERSION_CONFLICT"));
        mockMvc.perform(patch("/api/seatmaps/55/seats").header("Authorization", USER)
                        .contentType(MediaType.APPLICATION_JSON).content(VALID_EDIT))
                .andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.code").value("UNKNOWN_SEAT"));
        mockMvc.perform(patch("/api/seatmaps/55/seats").header("Authorization", USER)
                        .contentType(MediaType.APPLICATION_JSON).content(VALID_EDIT))
                .andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.code").value("DUPLICATE_SEAT_NUMBER"));
    }

    @Test
    void correctionReturns201AndConflictBodies() throws Exception {
        String body = "{\"uid\":\"s0003\",\"field\":\"ROW_LABEL\",\"value\":3,\"note\":\"현장에서 확인\"}";
        when(editService.reportCorrection(eq(55L), eq(1L), eq(false), any(CorrectionRequest.class)))
                .thenReturn(new CorrectionResponse(CorrectionStatus.PENDING, 7L))
                .thenReturn(new CorrectionResponse(CorrectionStatus.APPLIED, 8L))
                .thenThrow(new ConflictException("임시 좌석표는 직접 수정할 수 있어요."))
                .thenThrow(new ConflictException("이미 같은 내용으로 신고했어요."));

        mockMvc.perform(post("/api/seatmaps/55/corrections").header("Authorization", USER)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated())
                .andExpect(content().json("{\"status\":\"PENDING\",\"correctionId\":7}", true));
        mockMvc.perform(post("/api/seatmaps/55/corrections").header("Authorization", USER)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated())
                .andExpect(content().json("{\"status\":\"APPLIED\",\"correctionId\":8}", true));
        mockMvc.perform(post("/api/seatmaps/55/corrections").header("Authorization", USER)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isConflict())
                .andExpect(content().json("{\"message\":\"임시 좌석표는 직접 수정할 수 있어요.\"}", true));
        mockMvc.perform(post("/api/seatmaps/55/corrections").header("Authorization", USER)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isConflict())
                .andExpect(content().json("{\"message\":\"이미 같은 내용으로 신고했어요.\"}", true));
    }

    @Test
    void correctionRequestValidationIs400() throws Exception {
        for (String body : List.of(
                "{\"field\":\"ROW_LABEL\",\"value\":3}",
                "{\"uid\":\"s1\",\"value\":3}",
                "{\"uid\":\"s1\",\"field\":\"ROW_LABEL\"}",
                "{\"uid\":\"s1\",\"field\":\"ROW_LABEL\",\"value\":0}",
                "{\"uid\":\"s1\",\"field\":\"ROW_LABEL\",\"value\":3,\"note\":\"" + "x".repeat(501) + "\"}")) {
            mockMvc.perform(post("/api/seatmaps/55/corrections").header("Authorization", USER)
                            .contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isBadRequest());
        }
        verify(editService, never()).reportCorrection(any(), any(), any(Boolean.class), any());
    }

    @Test
    void limitAndLockErrorsRenderCodeAndMessage() throws Exception {
        when(editService.editSeats(eq(55L), eq(1L), eq(false), any()))
                .thenThrow(new SeatMapException(HttpStatus.TOO_MANY_REQUESTS, "EDIT_LIMIT_REACHED", "한도"))
                .thenThrow(new org.springframework.dao.CannotAcquireLockException("lock wait timeout"));

        mockMvc.perform(patch("/api/seatmaps/55/seats").header("Authorization", USER)
                        .contentType(MediaType.APPLICATION_JSON).content(VALID_EDIT))
                .andExpect(status().isTooManyRequests()).andExpect(jsonPath("$.code").value("EDIT_LIMIT_REACHED"));
        mockMvc.perform(patch("/api/seatmaps/55/seats").header("Authorization", USER)
                        .contentType(MediaType.APPLICATION_JSON).content(VALID_EDIT))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("BUSY"))
                .andExpect(jsonPath("$.message").value("요청이 몰려 처리하지 못했어요. 잠시 후 다시 시도해주세요."));
    }

    @Test
    void revisionListIsForbiddenForUserAndOkForAdmin() throws Exception {
        when(editService.listRevisions(55L, false, 0, 20)).thenThrow(new ForbiddenException("관리자만 볼 수 있어요."));
        SeatMapRevisionSummaryResponse row = new SeatMapRevisionSummaryResponse(101L, 2,
                SeatMapRevisionAction.CORRECTION_APPLIED, SeatMapStatus.OFFICIAL, null, "정정 신고 2건 자동 반영",
                LocalDateTime.of(2026, 10, 7, 12, 0, 1), 1);
        when(editService.listRevisions(55L, true, 1, 5))
                .thenReturn(new PageResponse<>(List.of(row), 1, 5, 6, 2));

        mockMvc.perform(get("/api/seatmaps/55/revisions").header("Authorization", USER))
                .andExpect(status().isForbidden())
                .andExpect(content().json("{\"message\":\"관리자만 볼 수 있어요.\"}", true));
        mockMvc.perform(get("/api/seatmaps/55/revisions?page=1&size=5").header("Authorization", ADMIN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].id").value(101))
                .andExpect(jsonPath("$.content[0].revisionNo").value(2))
                .andExpect(jsonPath("$.content[0].actionType").value("CORRECTION_APPLIED"))
                .andExpect(jsonPath("$.content[0].layoutStatus").value("OFFICIAL"))
                .andExpect(jsonPath("$.content[0].actorId").isEmpty())
                .andExpect(jsonPath("$.content[0].reason").value("정정 신고 2건 자동 반영"))
                .andExpect(jsonPath("$.content[0].createdAt").value("2026-10-07T12:00:01"))
                .andExpect(jsonPath("$.content[0].itemCount").value(1))
                .andExpect(jsonPath("$.totalElements").value(6))
                .andExpect(jsonPath("$.totalPages").value(2));
    }

    @Test
    void revisionDetailIsForbiddenForUserAndOkForAdmin() throws Exception {
        when(editService.getRevision(55L, 101L, false)).thenThrow(new ForbiddenException("관리자만 볼 수 있어요."));
        when(editService.getRevision(55L, 101L, true)).thenReturn(new SeatMapRevisionDetailResponse(101L, 2,
                SeatMapRevisionAction.USER_EDIT, SeatMapStatus.DRAFT, 1L, "사유", LocalDateTime.of(2026, 10, 7, 12, 0, 1),
                List.of(new SeatMapRevisionItemResponse(1L, "s0003", SeatMapField.ROW_LABEL, "2", "3", null))));

        mockMvc.perform(get("/api/seatmaps/55/revisions/101").header("Authorization", USER))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/seatmaps/55/revisions/101").header("Authorization", ADMIN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].seatUid").value("s0003"))
                .andExpect(jsonPath("$.items[0].field").value("ROW_LABEL"))
                .andExpect(jsonPath("$.items[0].beforeValue").value("2"))
                .andExpect(jsonPath("$.items[0].afterValue").value("3"))
                .andExpect(jsonPath("$.items[0].correctionId").isEmpty());
    }
}
