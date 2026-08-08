package com.incidentroom.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record RegisterRequest(
        @NotBlank @Email(message = "Enter a valid email address.") String email,
        @NotBlank @Size(min = 10, max = 128, message = "Password must be at least 10 characters.") String password,
        @NotBlank @Size(min = 2, max = 40, message = "Display name must be 2-40 characters.") String displayName
) {}
