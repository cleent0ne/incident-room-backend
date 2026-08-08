package com.incidentroom.dto;

import jakarta.validation.constraints.NotBlank;

public record StartAttemptRequest(@NotBlank String scenarioSlug) {}
