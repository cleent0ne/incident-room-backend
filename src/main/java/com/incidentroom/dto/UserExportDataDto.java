package com.incidentroom.dto;

import com.incidentroom.domain.Attempt;
import com.incidentroom.domain.LeaderboardEntry;

import java.time.Instant;
import java.util.List;

public record UserExportDataDto(
        UserResponse profile,
        Instant createdAt,
        Instant updatedAt,
        String lastPlayedDate,
        UserStatsDto stats,
        List<Attempt> attempts,
        List<LeaderboardEntry> leaderboardEntries,
        Instant exportedAt
) {
}
