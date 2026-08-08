package com.incidentroom.dto;

import com.incidentroom.domain.Scenario;

import java.util.List;

/**
 * The full public payload for GET /scenarios/{slug}. Two things are deliberately filtered out
 * here, not upstream in the domain model, so the full authored truth stays intact in MongoDB
 * for server-side use (scoring in Phase 3) while this DTO is the one place that decides what's
 * safe to ship to a browser:
 *
 *  1. Remediation actions' `outcome` (correctness/score/explanation) — the answer key.
 *  2. Telemetry events gated behind `revealedBy` — only ambient (always-visible) events belong
 *     on the static content page; gated events are revealed progressively during an attempt
 *     (Phase 3), not available up front.
 */
public record ScenarioDetailDto(
        String slug,
        String title,
        com.incidentroom.domain.Difficulty difficulty,
        String rootCauseClass,
        String symptomSummary,
        int durationTargetSeconds,
        TopologyDto topology,
        List<ScenarioActionDto> actions,
        List<TelemetryEventDto> ambientTelemetry,
        String postmortemMarkdown,
        ScenarioSeoDto seo
) {

    public static ScenarioDetailDto from(Scenario scenario) {
        List<ScenarioActionDto> actions = scenario.getActions() == null
                ? List.of()
                : scenario.getActions().stream().map(ScenarioActionDto::from).toList();

        List<TelemetryEventDto> ambientTelemetry = scenario.getTelemetry() == null
                ? List.of()
                : scenario.getTelemetry().stream()
                        .filter(event -> event.getRevealedBy() == null)
                        .map(TelemetryEventDto::from)
                        .toList();

        return new ScenarioDetailDto(
                scenario.getSlug(),
                scenario.getTitle(),
                scenario.getDifficulty(),
                scenario.getRootCauseClass(),
                scenario.getSymptomSummary(),
                scenario.getDurationTargetSeconds(),
                TopologyDto.from(scenario.getTopology()),
                actions,
                ambientTelemetry,
                scenario.getPostmortemMarkdown(),
                ScenarioSeoDto.from(scenario.getSeo())
        );
    }
}
