package com.seatswap.service;

import com.seatswap.dto.response.UserResponse;
import com.seatswap.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.authentication.InsufficientAuthenticationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 회원 정보 조회 (FR-01 회원 관리) */
@Service
@RequiredArgsConstructor
public class UserService {

    private final UserRepository userRepository;

    /**
     * 인증 주체의 userId로 내 정보를 조회한다.
     * 토큰은 유효했지만 그 사이 사용자가 삭제된 경우 AuthenticationException을 던진다
     * → GlobalExceptionHandler가 rethrow → ExceptionTranslationFilter → EntryPoint 401.
     */
    @Transactional(readOnly = true)
    public UserResponse getMe(Long userId) {
        return userRepository.findById(userId)
                .map(UserResponse::from)
                .orElseThrow(() -> new InsufficientAuthenticationException("인증된 사용자를 찾을 수 없습니다."));
    }
}
