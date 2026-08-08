package com.incidentroom.dto;

import jakarta.validation.constraints.NotNull;

public record PublishScenarioRequest(@NotNull Boolean publish) {}
