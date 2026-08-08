package com.incidentroom.dto;

import com.incidentroom.domain.AttemptStatus;

import java.time.Instant;

/**
 * One row of a user's attempt history (GET /api/v1/users/me/attempts). scenarioSlug comes
 * straight off the denormalized field on Attempt (no extra lookup); scenarioTitle is the one
 * thing Attempt doesn't carry, so AttemptService resolves it via a single bulk title lookup per
 * page rather than one Scenario fetch per row — see ScenarioService.getTitlesByIds.
 *
 * scenarioTitle is nullable: if a scenario is ever deleted after being played, history should
 * still render (with a missing title) rather than 500 or silently drop the row.
 */
public record AttemptSummaryDto(
        String attemptId,
        String scenarioSlug,
        String scenarioTitle,
        AttemptStatus status,
        Integer finalScore,
        Integer elapsedSeconds,
        Instant startedAt,
        Instant completedAt
) {}