package com.incidentroom.dto;

import com.incidentroom.domain.Difficulty;
import com.incidentroom.domain.Scenario;

import java.time.Instant;

/**
 * Row shape for GET /api/v1/admin/scenarios. Unlike the public {@link ScenarioSummaryDto}, this
 * carries the Mongo id (an admin needs it to act on the scenario — publish/unpublish, schedule,
 * delete, fetch full detail), the current publish state, and the daily-date assignment if any.
 *
 * This is what closes the gap the frontend's lib/admin-scenarios.ts localStorage bookkeeping was
 * standing in for: before this endpoint existed, the only place a scenario's id was ever surfaced
 * was the one-time ImportScenarioResponse, so an admin's device/browser was the only record of
 * "what scenarios exist." This endpoint makes the backend the source of truth instead.
 */
public record AdminScenarioSummaryDto(
        String id,
        String slug,
        String title,
        Difficulty difficulty,
        String rootCauseClass,
        boolean published,
        String dailyDate,
        Instant createdAt,
        Instant updatedAt) {

    public static AdminScenarioSummaryDto from(Scenario scenario) {
        return new AdminScenarioSummaryDto(
                scenario.getId(),
                scenario.getSlug(),
                scenario.getTitle(),
                scenario.getDifficulty(),
                scenario.getRootCauseClass(),
                scenario.isPublished(),
                scenario.getDailyDate(),
                scenario.getCreatedAt(),
                scenario.getUpdatedAt());
    }
}
