package com.seatswap.security;

import com.seatswap.domain.User;
import com.seatswap.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CustomUserDetailsServiceTest {

    private final UserRepository userRepository = mock(UserRepository.class);
    private final CustomUserDetailsService service = new CustomUserDetailsService(userRepository);

    @Test
    void loadUserByIdBuildsUserIdBasedPrincipal() {
        User user = User.create("a@b.com", "encoded", "닉네임");
        ReflectionTestUtils.setField(user, "id", 7L);
        when(userRepository.findById(7L)).thenReturn(Optional.of(user));

        AuthUserPrincipal principal = service.loadUserById(7L);

        assertThat(principal.userId()).isEqualTo(7L);
        assertThat(principal.email()).isEqualTo("a@b.com");
        assertThat(principal.getName()).isEqualTo("7");
        assertThat(principal.authorities()).extracting("authority").containsExactly("ROLE_USER");
        verify(userRepository, never()).findByEmail(anyString());
    }

    @Test
    void missingUserThrowsUsernameNotFound() {
        when(userRepository.findById(99L)).thenReturn(Optional.empty());

        // 필터가 이 예외만 잡아 미인증(→401)으로 처리한다
        assertThatThrownBy(() -> service.loadUserById(99L)).isInstanceOf(UsernameNotFoundException.class);
    }
}
