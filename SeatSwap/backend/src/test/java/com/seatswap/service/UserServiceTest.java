package com.seatswap.service;

import com.seatswap.domain.User;
import com.seatswap.dto.response.UserResponse;
import com.seatswap.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.AuthenticationException;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class UserServiceTest {

    private final UserRepository userRepository = mock(UserRepository.class);
    private final UserService userService = new UserService(userRepository);

    @Test
    void returnsUserResponseForExistingUser() {
        when(userRepository.findById(1L))
                .thenReturn(Optional.of(User.create("a@b.com", "encoded", "닉네임")));

        UserResponse me = userService.getMe(1L);

        assertThat(me.email()).isEqualTo("a@b.com");
        assertThat(me.nickname()).isEqualTo("닉네임");
        assertThat(me.trustScore()).isEqualTo(0.0);
    }

    @Test
    void missingUserIsAuthenticationFailure() {
        when(userRepository.findById(99L)).thenReturn(Optional.empty());

        // AuthenticationException이어야 Security가 401(EntryPoint)로 처리한다
        assertThatThrownBy(() -> userService.getMe(99L))
                .isInstanceOf(AuthenticationException.class);
    }

    @Test
    void looksUpByIdNeverByEmail() {
        when(userRepository.findById(1L)).thenReturn(Optional.of(User.create("a@b.com", "encoded", "닉네임")));

        userService.getMe(1L);

        org.mockito.Mockito.verify(userRepository, org.mockito.Mockito.never()).findByEmail(org.mockito.ArgumentMatchers.anyString());
    }
}
