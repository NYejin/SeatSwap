package com.seatswap.dto.response;

import com.seatswap.domain.User;

public record UserResponse(Long id, String email, String nickname, Double trustScore) {
    public static UserResponse from(User user) {
        return new UserResponse(user.getId(), user.getEmail(), user.getNickname(), user.getTrustScore());
    }
}
