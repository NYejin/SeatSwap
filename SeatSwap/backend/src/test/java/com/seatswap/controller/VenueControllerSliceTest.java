package com.seatswap.controller;

import com.seatswap.config.SecurityConfig;
import com.seatswap.dto.request.VenueCreateRequest;
import com.seatswap.dto.response.VenueResponse;
import com.seatswap.security.AuthUserPrincipal;
import com.seatswap.security.CustomUserDetailsService;
import com.seatswap.security.JwtAccessDeniedHandler;
import com.seatswap.security.JwtAuthenticationEntryPoint;
import com.seatswap.security.JwtTokenProvider;
import com.seatswap.security.SecurityErrorResponseWriter;
import com.seatswap.service.VenueService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = VenueController.class)
@Import({SecurityConfig.class, JwtAuthenticationEntryPoint.class, JwtAccessDeniedHandler.class,
        SecurityErrorResponseWriter.class})
class VenueControllerSliceTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private JwtTokenProvider tokenProvider;
    @MockBean
    private CustomUserDetailsService userDetailsService;
    @MockBean
    private VenueService venueService;

    @BeforeEach
    void setUp() {
        when(tokenProvider.validateToken("good")).thenReturn(true);
        when(tokenProvider.isRefreshToken("good")).thenReturn(false);
        when(tokenProvider.getUserId("good")).thenReturn(1L);
        when(userDetailsService.loadUserById(1L)).thenReturn(AuthUserPrincipal.of(1L, "a@b.com"));
    }

    @Test
    void requiresLogin() throws Exception {
        mockMvc.perform(get("/api/venues").param("query", "kspo"))
                .andExpect(status().isUnauthorized())
                .andExpect(content().json("{\"message\":\"로그인이 필요합니다.\"}", true));
    }

    @Test
    void searchReturnsArrayIncludingEmpty() throws Exception {
        when(venueService.search("kspo")).thenReturn(List.of(new VenueResponse(1L, "KSPO DOME", null)));
        when(venueService.search("없음")).thenReturn(List.of());

        mockMvc.perform(get("/api/venues").param("query", "kspo").header("Authorization", "Bearer good"))
                .andExpect(status().isOk())
                .andExpect(content().json("[{\"id\":1,\"name\":\"KSPO DOME\",\"address\":null}]", true));
        mockMvc.perform(get("/api/venues").param("query", "없음").header("Authorization", "Bearer good"))
                .andExpect(status().isOk())
                .andExpect(content().json("[]", true));
    }

    @Test
    void createIs201WhenNewAnd200WhenExisting() throws Exception {
        when(venueService.create(any(VenueCreateRequest.class)))
                .thenReturn(new VenueService.VenueCreateResult(new VenueResponse(5L, "올림픽홀", "서울"), true))
                .thenReturn(new VenueService.VenueCreateResult(new VenueResponse(5L, "올림픽홀", "서울"), false));

        String body = "{\"name\":\"올림픽홀\",\"address\":\"서울\"}";
        mockMvc.perform(post("/api/venues").header("Authorization", "Bearer good")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated())
                .andExpect(content().json("{\"id\":5,\"name\":\"올림픽홀\",\"address\":\"서울\"}", true));
        mockMvc.perform(post("/api/venues").header("Authorization", "Bearer good")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk());
    }

    @Test
    void createValidation() throws Exception {
        mockMvc.perform(post("/api/venues").header("Authorization", "Bearer good")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"  \"}"))
                .andExpect(status().isBadRequest())
                .andExpect(content().json("{\"name\":\"공연장 이름을 입력해주세요.\"}", true));
    }
}
