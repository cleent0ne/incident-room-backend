package com.incidentroom.dto;

import jakarta.validation.constraints.NotBlank;

public record TakeActionRequest(@NotBlank String actionKey) {}
