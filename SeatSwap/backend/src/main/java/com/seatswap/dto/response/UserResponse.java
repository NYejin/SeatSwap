package com.seatswap.dto.response;

import com.seatswap.domain.User;
import com.seatswap.domain.UserRole;

// role은 UI 분기용 표시값이다. 서버의 권한 판단은 항상 DB(매 요청 로드한 role) 기준이다.
public record UserResponse(Long id, String email, String nickname, Double trustScore, UserRole role) {
    public static UserResponse from(User user) {
        return new UserResponse(user.getId(), user.getEmail(), user.getNickname(), user.getTrustScore(), user.getRole());
    }
}
