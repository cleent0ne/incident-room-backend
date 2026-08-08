package com.incidentroom.dto;

import jakarta.validation.constraints.Pattern;

public record SetDailyDateRequest(
        @Pattern(regexp = "\\d{4}-\\d{2}-\\d{2}", message = "date must be in yyyy-MM-dd format")
        String date
) {}
