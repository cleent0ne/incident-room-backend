package com.incidentroom.dto;

import jakarta.validation.constraints.Size;

public record SaveReflectionRequest(
        @Size(max = 2000, message = "Reflection text must be 2000 characters or fewer")
        String text
) {}
