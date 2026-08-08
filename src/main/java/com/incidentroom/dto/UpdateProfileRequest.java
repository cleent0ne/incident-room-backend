package com.incidentroom.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Request body for PATCH /api/v1/users/me.
 * Only displayName is patchable via self-service — email changes require
 * a separate verification flow and password changes go through /auth/**.
 */
public record UpdateProfileRequest(
        @NotBlank(message = "Display name must not be blank.")
        @Size(min = 2, max = 40, message = "Display name must be 2–40 characters.")
        String displayName
) {}
