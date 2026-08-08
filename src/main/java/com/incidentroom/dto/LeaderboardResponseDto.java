package com.incidentroom.dto;

import java.util.List;

public record LeaderboardResponseDto(
        String scenarioSlug,
        List<LeaderboardEntryDto> entries,
        long totalEntries,
        int page,
        int size
) {}
