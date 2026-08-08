package com.incidentroom.dto;

import jakarta.validation.constraints.NotBlank;

public record ResolveAttemptRequest(@NotBlank String actionKey) {}
