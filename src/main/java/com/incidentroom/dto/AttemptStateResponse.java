package com.incidentroom.dto;

import com.incidentroom.domain.Attempt;
import com.incidentroom.domain.AttemptStatus;
import com.incidentroom.domain.Scenario;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Returned by both "start" and "resume/refresh" (GET /attempts/{id}) — the shape a client needs
 * to render the play screen from scratch either way. Deliberately built from BOTH the attempt
 * (what's happened so far) and the scenario (what's available/possible), rather than the two
 * being fetched separately by the frontend — one request, one consistent snapshot.
 */
public record AttemptStateResponse(
        String attemptId,
        String scenarioSlug,
        String scenarioTitle,
        String symptomSummary,
        int durationTargetSeconds,
        TopologyDto topology,
        List<ScenarioActionDto> actions,
        List<TelemetryEventDto> revealedTelemetry,
        List<String> actionsTakenKeys,
        AttemptStatus status,
        /**
         * Only present when the attempt is anonymous (userId == null). The frontend stores this
         * in local storage and supplies it on every subsequent mutating request via the
         * X-Capability-Token header. Null for logged-in attempts — they own the attempt by userId.
         */
        String capabilityToken
) {
    /** Ambient (always-visible) telemetry plus anything already revealed by actions taken so far. */
    public static AttemptStateResponse of(Attempt attempt, Scenario scenario) {
        Set<String> revealedByKeysTaken = attempt.getActionsTaken() == null
                ? Set.of()
                : attempt.getActionsTaken().stream().map(a -> a.getActionKey()).collect(Collectors.toSet());

        List<TelemetryEventDto> visible = scenario.getTelemetry() == null ? List.of() : scenario.getTelemetry().stream()
                .filter(t -> t.getRevealedBy() == null || revealedByKeysTaken.contains(t.getRevealedBy()))
                .map(TelemetryEventDto::from)
                .toList();

        List<ScenarioActionDto> actionDtos = scenario.getActions() == null ? List.of() : scenario.getActions().stream()
                .map(ScenarioActionDto::from)
                .toList();

        List<String> takenKeys = attempt.getActionsTaken() == null ? List.of() : attempt.getActionsTaken().stream()
                .map(a -> a.getActionKey())
                .toList();

        return new AttemptStateResponse(
                attempt.getId(),
                scenario.getSlug(),
                scenario.getTitle(),
                scenario.getSymptomSummary(),
                scenario.getDurationTargetSeconds(),
                TopologyDto.from(scenario.getTopology()),
                actionDtos,
                visible,
                takenKeys,
                attempt.getStatus(),
                // Only expose the capability token to anonymous callers — once an attempt is
                // claimed, the user's session is the credential; returning the token afterwards
                // would be pointless and slightly increases its surface exposure.
                attempt.getUserId() == null ? attempt.getCapabilityToken() : null);
    }
}
