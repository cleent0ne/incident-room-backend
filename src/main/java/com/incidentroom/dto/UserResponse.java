package com.incidentroom.dto;

import com.incidentroom.domain.AppUser;
import com.incidentroom.domain.Role;

import java.time.Instant;

public record UserResponse(
        String id,
        String email,
        String displayName,
        boolean emailVerified,
        Role role,
        int currentStreak,
        String oauthProvider,
        Instant createdAt
) {
    public static UserResponse from(AppUser user) {
        return new UserResponse(user.getId(), user.getEmail(), user.getDisplayName(),
                user.isEmailVerified(), user.getRole(), user.getCurrentStreak(),
                user.getOauthProvider(), user.getCreatedAt());
    }
}
