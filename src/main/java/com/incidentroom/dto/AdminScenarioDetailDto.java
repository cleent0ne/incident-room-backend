package com.incidentroom.dto;

import com.incidentroom.domain.Difficulty;
import com.incidentroom.domain.Scenario;

import java.time.Instant;
import java.util.List;

/**
 * Full admin view for GET /api/v1/admin/scenarios/{id} — the complete authored truth, including
 * everything the public {@link ScenarioDetailDto} deliberately strips (remediation outcomes,
 * gated telemetry, `revealedBy` references). This is what lets an admin actually review a
 * scenario's answer key and gating structure without going into Mongo directly, and it's what a
 * future content-authoring UI (see the product analysis, gap 3.8) would build a form around.
 *
 * Safe because this endpoint sits behind the admin filter chain (ROLE_ADMIN only, 404 to anyone
 * else) — see SecurityConfig.adminFilterChain.
 */
public record AdminScenarioDetailDto(
        String id,
        String slug,
        String title,
        Difficulty difficulty,
        String rootCauseClass,
        String symptomSummary,
        int durationTargetSeconds,
        boolean published,
        String dailyDate,
        TopologyDto topology,
        List<AdminScenarioActionDto> actions,
        List<AdminTelemetryEventDto> telemetry,
        String postmortemMarkdown,
        ScenarioSeoDto seo,
        Instant createdAt,
        Instant updatedAt) {

    public static AdminScenarioDetailDto from(Scenario scenario) {
        List<AdminScenarioActionDto> actions = scenario.getActions() == null
                ? List.of()
                : scenario.getActions().stream().map(AdminScenarioActionDto::from).toList();

        List<AdminTelemetryEventDto> telemetry = scenario.getTelemetry() == null
                ? List.of()
                : scenario.getTelemetry().stream().map(AdminTelemetryEventDto::from).toList();

        return new AdminScenarioDetailDto(
                scenario.getId(),
                scenario.getSlug(),
                scenario.getTitle(),
                scenario.getDifficulty(),
                scenario.getRootCauseClass(),
                scenario.getSymptomSummary(),
                scenario.getDurationTargetSeconds(),
                scenario.isPublished(),
                scenario.getDailyDate(),
                TopologyDto.from(scenario.getTopology()),
                actions,
                telemetry,
                scenario.getPostmortemMarkdown(),
                ScenarioSeoDto.from(scenario.getSeo()),
                scenario.getCreatedAt(),
                scenario.getUpdatedAt());
    }
}
