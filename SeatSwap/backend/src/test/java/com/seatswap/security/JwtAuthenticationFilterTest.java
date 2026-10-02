package com.seatswap.security;

import io.jsonwebtoken.MalformedJwtException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.security.authentication.AuthenticationServiceException;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.ArgumentMatchers.anyString;

class JwtAuthenticationFilterTest {

    private JwtTokenProvider tokenProvider;
    private CustomUserDetailsService userDetailsService;
    private JwtAuthenticationFilter filter;

    @BeforeEach
    void setUp() {
        tokenProvider = mock(JwtTokenProvider.class);
        userDetailsService = mock(CustomUserDetailsService.class);
        filter = new JwtAuthenticationFilter(tokenProvider, userDetailsService);
        SecurityContextHolder.clearContext();
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    /** 체인 다음 단계에서 본 SecurityContext의 인증 정보를 캡처한다. */
    private Authentication runFilter(String authorizationHeader, MockHttpServletResponse response) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/tickets");
        if (authorizationHeader != null) {
            request.addHeader("Authorization", authorizationHeader);
        }
        AtomicReference<Authentication> seen = new AtomicReference<>();
        AtomicReference<Boolean> chainCalled = new AtomicReference<>(false);
        MockFilterChain chain = new MockFilterChain() {
            @Override
            public void doFilter(jakarta.servlet.ServletRequest req, jakarta.servlet.ServletResponse res) {
                chainCalled.set(true);
                seen.set(SecurityContextHolder.getContext().getAuthentication());
            }
        };
        filter.doFilter(request, response, chain);
        assertThat(chainCalled.get()).as("filter must always continue the chain").isTrue();
        return seen.get();
    }

    @Test
    void validAccessTokenAuthenticates() throws Exception {
        when(tokenProvider.validateToken("good")).thenReturn(true);
        when(tokenProvider.isRefreshToken("good")).thenReturn(false);
        when(tokenProvider.getEmail("good")).thenReturn("a@b.com");
        when(userDetailsService.loadUserByUsername("a@b.com")).thenReturn(
                new User("a@b.com", "pw", List.of(new SimpleGrantedAuthority("ROLE_USER"))));

        Authentication auth = runFilter("Bearer good", new MockHttpServletResponse());

        assertThat(auth).isNotNull();
        assertThat(auth.getName()).isEqualTo("a@b.com");
    }

    @Test
    void deletedUserTokenLeavesContextEmptyAndDoesNotWriteResponse() throws Exception {
        when(tokenProvider.validateToken("ghost")).thenReturn(true);
        when(tokenProvider.isRefreshToken("ghost")).thenReturn(false);
        when(tokenProvider.getEmail("ghost")).thenReturn("ghost@b.com");
        when(userDetailsService.loadUserByUsername("ghost@b.com"))
                .thenThrow(new UsernameNotFoundException("not found"));
        MockHttpServletResponse response = new MockHttpServletResponse();

        Authentication auth = runFilter("Bearer ghost", response);

        assertThat(auth).isNull();
        // 필터는 응답을 직접 쓰지 않는다 (401은 EntryPoint 책임)
        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(response.getContentAsString()).isEmpty();
        assertThat(response.isCommitted()).isFalse();
    }

    @Test
    void jwtParsingErrorLeavesContextEmpty() throws Exception {
        when(tokenProvider.validateToken("weird")).thenReturn(true);
        when(tokenProvider.isRefreshToken("weird")).thenThrow(new MalformedJwtException("bad"));

        assertThat(runFilter("Bearer weird", new MockHttpServletResponse())).isNull();
    }

    @Test
    void refreshTokenIsNotAcceptedAsAccessToken() throws Exception {
        when(tokenProvider.validateToken("refresh")).thenReturn(true);
        when(tokenProvider.isRefreshToken("refresh")).thenReturn(true);

        assertThat(runFilter("Bearer refresh", new MockHttpServletResponse())).isNull();
        verify(userDetailsService, never()).loadUserByUsername(anyString());
    }

    @Test
    void invalidOrMissingTokenLeavesContextEmpty() throws Exception {
        when(tokenProvider.validateToken("expired")).thenReturn(false);

        assertThat(runFilter("Bearer expired", new MockHttpServletResponse())).isNull();
        assertThat(runFilter(null, new MockHttpServletResponse())).isNull();
        assertThat(runFilter("Basic abc", new MockHttpServletResponse())).isNull();
    }

    @Test
    void infrastructureErrorsAreNotDisguisedAs401() {
        when(tokenProvider.validateToken("good")).thenReturn(true);
        when(tokenProvider.isRefreshToken("good")).thenReturn(false);
        when(tokenProvider.getEmail("good")).thenReturn("a@b.com");
        when(userDetailsService.loadUserByUsername("a@b.com"))
                .thenThrow(new DataAccessResourceFailureException("db down"));

        assertThatThrownBy(() -> runFilter("Bearer good", new MockHttpServletResponse()))
                .isInstanceOf(DataAccessResourceFailureException.class);
    }

    @Test
    void authenticationServiceExceptionIsNotSwallowed() {
        when(tokenProvider.validateToken("good")).thenReturn(true);
        when(tokenProvider.isRefreshToken("good")).thenReturn(false);
        when(tokenProvider.getEmail("good")).thenReturn("a@b.com");
        when(userDetailsService.loadUserByUsername("a@b.com"))
                .thenThrow(new AuthenticationServiceException("user store failure"));

        assertThatThrownBy(() -> runFilter("Bearer good", new MockHttpServletResponse()))
                .isInstanceOf(AuthenticationServiceException.class);
    }

    @Test
    void dataAnomalyIllegalArgumentIsNotSwallowed() {
        when(tokenProvider.validateToken("odd")).thenReturn(true);
        when(tokenProvider.isRefreshToken("odd")).thenReturn(false);
        when(tokenProvider.getEmail("odd")).thenThrow(new IllegalArgumentException("unexpected claim"));

        assertThatThrownBy(() -> runFilter("Bearer odd", new MockHttpServletResponse()))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
