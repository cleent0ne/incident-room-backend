package com.incidentroom.domain;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;

/**
 * Stores a user's single best score for a specific scenario on the global leaderboard.
 * Upserted when an attempt is resolved or claimed if it improves upon the user's prior high score.
 */
@Document(collection = "leaderboard_entries")
@CompoundIndex(name = "scenario_user_unique_idx", def = "{'scenarioSlug': 1, 'userId': 1}", unique = true)
@CompoundIndex(name = "scenario_ranking_idx", def = "{'scenarioSlug': 1, 'score': -1, 'elapsedSeconds': 1, 'achievedAt': 1}")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class LeaderboardEntry {

    @Id
    private String id;

    @Indexed
    private String scenarioSlug;

    private String scenarioId;

    @Indexed
    private String userId;

    private String displayName;

    private int score;

    private int elapsedSeconds;

    private String attemptId;

    private Instant achievedAt;
}
