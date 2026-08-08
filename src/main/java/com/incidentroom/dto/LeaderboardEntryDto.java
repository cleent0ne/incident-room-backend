package com.incidentroom.dto;

import com.incidentroom.domain.LeaderboardEntry;

import java.time.Instant;

public record LeaderboardEntryDto(
        int rank,
        String userId,
        String displayName,
        int score,
        int elapsedSeconds,
        Instant achievedAt
) {
    public static LeaderboardEntryDto from(LeaderboardEntry entry, int rank) {
        return new LeaderboardEntryDto(
                rank,
                entry.getUserId(),
                entry.getDisplayName(),
                entry.getScore(),
                entry.getElapsedSeconds(),
                entry.getAchievedAt()
        );
    }
}
