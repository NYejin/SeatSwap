package com.seatswap.controller;

import com.seatswap.config.SecurityConfig;
import com.seatswap.dto.response.ExchangeMatchResponse;
import com.seatswap.exception.BusinessRuleException;
import com.seatswap.exception.ConflictException;
import com.seatswap.exception.ForbiddenException;
import com.seatswap.exception.NotFoundException;
import com.seatswap.security.AuthUserPrincipal;
import com.seatswap.security.CustomUserDetailsService;
import com.seatswap.security.JwtAccessDeniedHandler;
import com.seatswap.security.JwtAuthenticationEntryPoint;
import com.seatswap.security.JwtTokenProvider;
import com.seatswap.security.SecurityErrorResponseWriter;
import com.seatswap.service.ExchangeMatchService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.time.LocalDateTime;
import java.util.Map;

import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** /api/exchange/requests/{id}/proposals, /api/exchange/matches/{id}/... — 실제 SecurityConfig + GlobalExceptionHandler, 서비스는 mock. */
@WebMvcTest(controllers = ExchangeMatchController.class)
@Import({SecurityConfig.class, JwtAuthenticationEntryPoint.class, JwtAccessDeniedHandler.class,
        SecurityErrorResponseWriter.class})
class ExchangeMatchControllerSliceTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private JwtTokenProvider tokenProvider;
    @MockBean
    private CustomUserDetailsService userDetailsService;
    @MockBean
    private ExchangeMatchService matchService;

    @BeforeEach
    void setUp() {
        when(tokenProvider.validateToken("good")).thenReturn(true);
        when(tokenProvider.isRefreshToken("good")).thenReturn(false);
        when(tokenProvider.getUserId("good")).thenReturn(1L);
        when(userDetailsService.loadUserById(1L)).thenReturn(AuthUserPrincipal.of(1L, "a@b.com"));
    }

    private static MockHttpServletRequestBuilder auth(MockHttpServletRequestBuilder builder) {
        return builder.header("Authorization", "Bearer good");
    }

    private static ExchangeMatchResponse response(String status) {
        return new ExchangeMatchResponse(50L, status, "A", 900L, 700L, 800L, 600L, "상대",
                LocalDateTime.of(2026, 10, 8, 12, 0, 5), null, null, null,
                LocalDateTime.of(2026, 10, 8, 11, 0, 0), LocalDateTime.of(2026, 10, 8, 12, 0, 5));
    }

    @Test
    void allEndpointsRequireLogin() throws Exception {
        mockMvc.perform(post("/api/exchange/requests/900/proposals").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"targetRequestId\":800}"))
                .andExpect(status().isUnauthorized())
                .andExpect(content().json("{\"message\":\"로그인이 필요합니다.\"}", true));
        for (String action : new String[]{"accept", "reject", "cancel"}) {
            mockMvc.perform(post("/api/exchange/matches/50/" + action)).andExpect(status().isUnauthorized())
                    .andExpect(content().json("{\"message\":\"로그인이 필요합니다.\"}", true));
        }
    }

    @Test
    void proposeReturns201WithMatchShape() throws Exception {
        when(matchService.propose(1L, 900L, 800L)).thenReturn(new ExchangeMatchResponse(50L, "CHATTING", "A",
                900L, 700L, 800L, 600L, "상대", null, null, null, null,
                LocalDateTime.of(2026, 10, 8, 11, 0, 0), LocalDateTime.of(2026, 10, 8, 11, 0, 0)));

        mockMvc.perform(auth(post("/api/exchange/requests/900/proposals")).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"targetRequestId\":800}"))
                .andExpect(status().isCreated())
                .andExpect(content().json("""
                        {"id":50,"status":"CHATTING","mySide":"A","myRequestId":900,"myTicketId":700,
                         "counterpartRequestId":800,"counterpartTicketId":600,"counterpartNickname":"상대",
                         "myReservedAt":null,"counterpartReservedAt":null,"canceledBy":null,"canceledAt":null,
                         "createdAt":"2026-10-08T11:00:00","updatedAt":"2026-10-08T11:00:00"}
                        """, true));
    }

    @Test
    void proposeValidatesBody() throws Exception {
        mockMvc.perform(auth(post("/api/exchange/requests/900/proposals")).contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(content().json("{\"targetRequestId\":\"교환을 제안할 상대 요청을 선택해주세요.\"}", true));
        mockMvc.perform(auth(post("/api/exchange/requests/900/proposals")).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"targetRequestId\":\"abc\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(content().json("{\"message\":\"요청 형식이 올바르지 않습니다.\"}", true));
        mockMvc.perform(auth(post("/api/exchange/requests/abc/proposals")).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"targetRequestId\":800}"))
                .andExpect(status().isBadRequest());
        verify(matchService, never()).propose(anyLong(), anyLong(), anyLong());
    }

    @Test
    void proposeMapsErrorCodes() throws Exception {
        when(matchService.propose(1L, 901L, 800L)).thenThrow(new ForbiddenException("본인"));
        when(matchService.propose(1L, 902L, 800L)).thenThrow(new NotFoundException("없음"));
        when(matchService.propose(1L, 903L, 800L)).thenThrow(new ConflictException("진행 중",
                Map.of("code", "MATCH_ALREADY_OPEN", "matchId", 7L)));
        when(matchService.propose(1L, 904L, 800L)).thenThrow(new BusinessRuleException("NOT_A_CANDIDATE", "조건"));

        String body = "{\"targetRequestId\":800}";
        mockMvc.perform(auth(post("/api/exchange/requests/901/proposals")).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isForbidden()).andExpect(content().json("{\"message\":\"본인\"}", true));
        mockMvc.perform(auth(post("/api/exchange/requests/902/proposals")).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isNotFound()).andExpect(content().json("{\"message\":\"없음\"}", true));
        mockMvc.perform(auth(post("/api/exchange/requests/903/proposals")).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isConflict())
                .andExpect(content().json("{\"message\":\"진행 중\",\"code\":\"MATCH_ALREADY_OPEN\",\"matchId\":7}", true));
        mockMvc.perform(auth(post("/api/exchange/requests/904/proposals")).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(content().json("{\"code\":\"NOT_A_CANDIDATE\",\"message\":\"조건\"}", true));
    }

    @Test
    void acceptRejectCancelReturn200WithMatch() throws Exception {
        when(matchService.accept(1L, 50L)).thenReturn(response("CHATTING"));
        when(matchService.reject(1L, 51L)).thenReturn(response("CANCELED"));
        when(matchService.cancel(1L, 52L)).thenReturn(response("CANCELED"));

        mockMvc.perform(auth(post("/api/exchange/matches/50/accept"))).andExpect(status().isOk())
                .andExpect(content().json("{\"id\":50,\"status\":\"CHATTING\",\"myReservedAt\":\"2026-10-08T12:00:05\"}"));
        mockMvc.perform(auth(post("/api/exchange/matches/51/reject"))).andExpect(status().isOk())
                .andExpect(content().json("{\"status\":\"CANCELED\"}"));
        mockMvc.perform(auth(post("/api/exchange/matches/52/cancel"))).andExpect(status().isOk())
                .andExpect(content().json("{\"status\":\"CANCELED\"}"));
    }

    @Test
    void actionsMapErrorCodes() throws Exception {
        when(matchService.accept(1L, 61L)).thenThrow(new ForbiddenException("참여자"));
        when(matchService.accept(1L, 62L)).thenThrow(new NotFoundException("없음"));
        when(matchService.accept(1L, 63L)).thenThrow(new ConflictException("이미 취소된 매칭입니다.",
                Map.of("code", "MATCH_STATE_CONFLICT", "status", "CANCELED", "action", "ACCEPT")));
        when(matchService.cancel(1L, 64L)).thenThrow(new ConflictException("예약됨",
                Map.of("code", "TICKET_ALREADY_RESERVED")));

        mockMvc.perform(auth(post("/api/exchange/matches/61/accept"))).andExpect(status().isForbidden());
        mockMvc.perform(auth(post("/api/exchange/matches/62/accept"))).andExpect(status().isNotFound());
        mockMvc.perform(auth(post("/api/exchange/matches/63/accept"))).andExpect(status().isConflict())
                .andExpect(content().json("""
                        {"message":"이미 취소된 매칭입니다.","code":"MATCH_STATE_CONFLICT","status":"CANCELED","action":"ACCEPT"}
                        """, true));
        mockMvc.perform(auth(post("/api/exchange/matches/64/cancel"))).andExpect(status().isConflict())
                .andExpect(content().json("{\"message\":\"예약됨\",\"code\":\"TICKET_ALREADY_RESERVED\"}", true));
    }

    @Test
    void nonNumericMatchIdIs400AndGetIsNotAllowed() throws Exception {
        mockMvc.perform(auth(post("/api/exchange/matches/abc/accept"))).andExpect(status().isBadRequest());
        mockMvc.perform(auth(get("/api/exchange/matches/50/accept"))).andExpect(status().isMethodNotAllowed());
        verify(matchService, never()).accept(anyLong(), any());
    }
}
