package com.seatswap.controller;

import com.seatswap.config.SecurityConfig;
import com.seatswap.domain.SeatMapStatus;
import com.seatswap.dto.response.SeatMapResponse;
import com.seatswap.dto.response.SeatMapSeat;
import com.seatswap.dto.response.SeatMapSummaryResponse;
import com.seatswap.exception.ConflictException;
import com.seatswap.exception.ForbiddenException;
import com.seatswap.exception.NotFoundException;
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
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = SeatMapController.class)
@Import({SecurityConfig.class, JwtAuthenticationEntryPoint.class, JwtAccessDeniedHandler.class,
        SecurityErrorResponseWriter.class})
class SeatMapControllerSliceTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private JwtTokenProvider tokenProvider;
    @MockBean
    private CustomUserDetailsService userDetailsService;
    @MockBean
    private SeatMapService seatMapService;
    @MockBean
    private SeatMapEditService seatMapEditService;

    private static final String AUTH = "Bearer good";

    @BeforeEach
    void setUp() {
        when(tokenProvider.validateToken("good")).thenReturn(true);
        when(tokenProvider.isRefreshToken("good")).thenReturn(false);
        when(tokenProvider.getUserId("good")).thenReturn(1L);
        when(userDetailsService.loadUserById(1L)).thenReturn(AuthUserPrincipal.of(1L, "a@b.com"));
    }

    private static MockMultipartFile png() {
        return new MockMultipartFile("file", "a.png", "image/png", new byte[]{1, 2, 3});
    }

    private static SeatMapResponse response() {
        return new SeatMapResponse(55L, 10L, "KSPO DOME", "A구역", SeatMapStatus.DRAFT, 1, 700, 400,
                List.of(new SeatMapSeat("s0001", 1, 1, 70, 40, 18, 18)),
                LocalDateTime.of(2026, 10, 7, 12, 0, 1), LocalDateTime.of(2026, 10, 7, 12, 0, 1), true);
    }

    @Test
    void allEndpointsRequireLogin() throws Exception {
        mockMvc.perform(multipart("/api/venues/10/seatmaps").file(png())).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/venues/10/seatmaps")).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/seatmaps/55")).andExpect(status().isUnauthorized())
                .andExpect(content().json("{\"message\":\"로그인이 필요합니다.\"}", true));
    }

    @Test
    void createReturns201WithSeatMap() throws Exception {
        when(seatMapService.createDraft(eq(10L), eq(1L), eq(false), any(), eq("A구역"), eq("skip"))).thenReturn(response());

        mockMvc.perform(multipart("/api/venues/10/seatmaps").file(png())
                        .param("zoneName", "A구역").param("aisleMode", "skip").header("Authorization", AUTH))
                .andExpect(status().isCreated())
                .andExpect(content().json("""
                        {"id":55,"venueId":10,"venueName":"KSPO DOME","zoneName":"A구역","status":"DRAFT","version":1,
                         "imageWidth":700,"imageHeight":400,
                         "seats":[{"uid":"s0001","row":1,"col":1,"x":70,"y":40,"w":18,"h":18,"section":1}],
                         "createdAt":"2026-10-07T12:00:01","updatedAt":"2026-10-07T12:00:01","canDelete":true}""", true));
    }

    @Test
    void optionalFieldsMayBeOmitted() throws Exception {
        when(seatMapService.createDraft(eq(10L), eq(1L), eq(false), any(), eq(null), eq(null))).thenReturn(response());

        mockMvc.perform(multipart("/api/venues/10/seatmaps").file(png()).header("Authorization", AUTH))
                .andExpect(status().isCreated());
    }

    @Test
    void missingFilePartIs400() throws Exception {
        mockMvc.perform(multipart("/api/venues/10/seatmaps").param("zoneName", "A").header("Authorization", AUTH))
                .andExpect(status().isBadRequest());
    }

    @Test
    void nonMultipartBodyIsRejected() throws Exception {
        mockMvc.perform(post("/api/venues/10/seatmaps").contentType("application/json").content("{}")
                        .header("Authorization", AUTH))
                .andExpect(status().isUnsupportedMediaType());
    }

    @Test
    void invalidVenueIdIs400() throws Exception {
        mockMvc.perform(multipart("/api/venues/abc/seatmaps").file(png()).header("Authorization", AUTH))
                .andExpect(status().isBadRequest());
    }

    @Test
    void seatMapExceptionsRenderCodeAndMessage() throws Exception {
        when(seatMapService.createDraft(any(), any(), anyBoolean(), any(), any(), any()))
                .thenThrow(new SeatMapException(HttpStatus.UNPROCESSABLE_ENTITY, "NO_SEATS_DETECTED", "좌석을 찾지 못했습니다."));

        mockMvc.perform(multipart("/api/venues/10/seatmaps").file(png()).header("Authorization", AUTH))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(content().json("{\"code\":\"NO_SEATS_DETECTED\",\"message\":\"좌석을 찾지 못했습니다.\"}", true));
    }

    @Test
    void duplicateDraftIs409WithSeatMapId() throws Exception {
        when(seatMapService.createDraft(any(), any(), anyBoolean(), any(), any(), any()))
                .thenThrow(new ConflictException("이미 등록된 임시 좌석표가 있습니다.", Map.of("seatMapId", 77L)));

        mockMvc.perform(multipart("/api/venues/10/seatmaps").file(png()).header("Authorization", AUTH))
                .andExpect(status().isConflict())
                .andExpect(content().json("{\"message\":\"이미 등록된 임시 좌석표가 있습니다.\",\"seatMapId\":77}", true));
    }

    @Test
    void oversizedUploadIs413() throws Exception {
        when(seatMapService.createDraft(any(), any(), anyBoolean(), any(), any(), any()))
                .thenThrow(new MaxUploadSizeExceededException(10L * 1024 * 1024));

        mockMvc.perform(multipart("/api/venues/10/seatmaps").file(png()).header("Authorization", AUTH))
                .andExpect(status().isPayloadTooLarge())
                .andExpect(content().json("{\"code\":\"IMAGE_TOO_LARGE\",\"message\":\"이미지는 10MB 이하만 올릴 수 있습니다.\"}", true));
    }

    @Test
    void getReturnsSeatMapAnd404() throws Exception {
        when(seatMapService.get(55L, 1L, false)).thenReturn(response());
        when(seatMapService.get(404L, 1L, false)).thenThrow(new NotFoundException("좌석표를 찾을 수 없습니다."));

        mockMvc.perform(get("/api/seatmaps/55").header("Authorization", AUTH))
                .andExpect(status().isOk())
                .andExpect(content().json("{\"id\":55,\"status\":\"DRAFT\",\"canDelete\":true,\"seats\":[{\"uid\":\"s0001\"}]}", false));
        mockMvc.perform(get("/api/seatmaps/404").header("Authorization", AUTH))
                .andExpect(status().isNotFound())
                .andExpect(content().json("{\"message\":\"좌석표를 찾을 수 없습니다.\"}", true));
    }

    @Test
    void listReturnsArrayIncludingEmpty() throws Exception {
        when(seatMapService.listByVenue(10L)).thenReturn(List.of(
                new SeatMapSummaryResponse(55L, null, SeatMapStatus.DRAFT, 1, 2, LocalDateTime.of(2026, 10, 7, 12, 0, 1))));
        when(seatMapService.listByVenue(11L)).thenReturn(List.of());

        mockMvc.perform(get("/api/venues/10/seatmaps").header("Authorization", AUTH))
                .andExpect(status().isOk())
                .andExpect(content().json(
                        "[{\"id\":55,\"zoneName\":null,\"status\":\"DRAFT\",\"version\":1,\"seatCount\":2,\"createdAt\":\"2026-10-07T12:00:01\"}]", true));
        mockMvc.perform(get("/api/venues/11/seatmaps").header("Authorization", AUTH))
                .andExpect(status().isOk()).andExpect(content().json("[]", true));
    }

    @Test
    void zoneAndDailyLimitErrorsRenderCodeAndMessage() throws Exception {
        when(seatMapService.createDraft(any(), any(), anyBoolean(), any(), any(), any()))
                .thenThrow(new SeatMapException(HttpStatus.UNPROCESSABLE_ENTITY, "ZONE_LIMIT_REACHED",
                        "이 공연장에 등록할 수 있는 구역 수를 넘었어요."))
                .thenThrow(new SeatMapException(HttpStatus.TOO_MANY_REQUESTS, "DAILY_LIMIT_REACHED",
                        "하루에 등록할 수 있는 좌석표 수를 넘었어요. 내일 다시 시도해주세요."));

        mockMvc.perform(multipart("/api/venues/10/seatmaps").file(png()).header("Authorization", AUTH))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(content().json(
                        "{\"code\":\"ZONE_LIMIT_REACHED\",\"message\":\"이 공연장에 등록할 수 있는 구역 수를 넘었어요.\"}", true));
        mockMvc.perform(multipart("/api/venues/10/seatmaps").file(png()).header("Authorization", AUTH))
                .andExpect(status().isTooManyRequests())
                .andExpect(content().json(
                        "{\"code\":\"DAILY_LIMIT_REACHED\",\"message\":\"하루에 등록할 수 있는 좌석표 수를 넘었어요. 내일 다시 시도해주세요.\"}", true));
    }

    @Test
    void adminRoleFromPrincipalIsPassedToService() throws Exception {
        when(tokenProvider.getUserId("admin")).thenReturn(9L);
        when(tokenProvider.validateToken("admin")).thenReturn(true);
        when(tokenProvider.isRefreshToken("admin")).thenReturn(false);
        when(userDetailsService.loadUserById(9L))
                .thenReturn(AuthUserPrincipal.of(9L, "admin@b.com", com.seatswap.domain.UserRole.ADMIN));

        mockMvc.perform(delete("/api/seatmaps/55").header("Authorization", "Bearer admin"))
                .andExpect(status().isNoContent());
        verify(seatMapService).deleteDraft(55L, 9L, true);
    }

    @Test
    void deleteRequiresLoginAndReturns204() throws Exception {
        mockMvc.perform(delete("/api/seatmaps/55")).andExpect(status().isUnauthorized());

        mockMvc.perform(delete("/api/seatmaps/55").header("Authorization", AUTH))
                .andExpect(status().isNoContent());
        verify(seatMapService).deleteDraft(55L, 1L, false);
    }

    @Test
    void deleteErrorsRender403404And409() throws Exception {
        doThrow(new NotFoundException("좌석표를 찾을 수 없습니다.")).when(seatMapService).deleteDraft(404L, 1L, false);
        doThrow(new ConflictException("정식 등록된 좌석표는 삭제할 수 없습니다.")).when(seatMapService).deleteDraft(9L, 1L, false);
        doThrow(new ForbiddenException("작성자 또는 관리자만 삭제할 수 있어요.")).when(seatMapService).deleteDraft(8L, 1L, false);

        mockMvc.perform(delete("/api/seatmaps/404").header("Authorization", AUTH))
                .andExpect(status().isNotFound());
        mockMvc.perform(delete("/api/seatmaps/9").header("Authorization", AUTH))
                .andExpect(status().isConflict())
                .andExpect(content().json("{\"message\":\"정식 등록된 좌석표는 삭제할 수 없습니다.\"}", true));
        mockMvc.perform(delete("/api/seatmaps/8").header("Authorization", AUTH))
                .andExpect(status().isForbidden())
                .andExpect(content().json("{\"message\":\"작성자 또는 관리자만 삭제할 수 있어요.\"}", true));
    }
}
