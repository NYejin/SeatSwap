package com.seatswap.service;

import com.seatswap.domain.User;
import com.seatswap.dto.request.LoginRequest;
import com.seatswap.dto.request.RefreshRequest;
import com.seatswap.dto.request.SignupRequest;
import com.seatswap.dto.response.TokenResponse;
import com.seatswap.dto.response.UserResponse;
import com.seatswap.exception.SeatSwapException;
import com.seatswap.repository.UserRepository;
import com.seatswap.security.JwtTokenProvider;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 회원가입/로그인/JWT 발급·재발급 (FR-01) */
@Service
@RequiredArgsConstructor
public class AuthService {

    private static final String LOGIN_FAIL_MESSAGE = "이메일 또는 비밀번호가 올바르지 않습니다.";

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtTokenProvider jwtTokenProvider;

    @Transactional
    public UserResponse signup(SignupRequest request) {
        if (userRepository.existsByEmail(request.email())) {
            throw new SeatSwapException("이미 가입된 이메일입니다.");
        }
        User user = User.create(request.email(), passwordEncoder.encode(request.password()), request.nickname());
        return UserResponse.from(userRepository.save(user));
    }

    public TokenResponse login(LoginRequest request) {
        User user = userRepository.findByEmail(request.email())
                .orElseThrow(() -> new SeatSwapException(LOGIN_FAIL_MESSAGE));

        if (!passwordEncoder.matches(request.password(), user.getPassword())) {
            throw new SeatSwapException(LOGIN_FAIL_MESSAGE);
        }

        return issueTokens(user);
    }

    public TokenResponse refresh(RefreshRequest request) {
        String token = request.refreshToken();

        if (!jwtTokenProvider.validateToken(token) || !jwtTokenProvider.isRefreshToken(token)) {
            throw new SeatSwapException("유효하지 않거나 만료된 refresh token 입니다.");
        }

        Long userId = jwtTokenProvider.getUserId(token);
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new SeatSwapException("사용자를 찾을 수 없습니다."));

        return issueTokens(user);
    }

    private TokenResponse issueTokens(User user) {
        String accessToken = jwtTokenProvider.generateAccessToken(user.getId(), user.getEmail());
        String refreshToken = jwtTokenProvider.generateRefreshToken(user.getId(), user.getEmail());
        return TokenResponse.of(accessToken, refreshToken);
    }
}
