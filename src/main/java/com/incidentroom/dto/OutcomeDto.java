package com.incidentroom.dto;

import com.incidentroom.domain.Correctness;
import com.incidentroom.domain.Outcome;

/**
 * Unlike {@link ScenarioActionDto} (the public shape, which deliberately omits this), admin
 * endpoints are allowed to see the answer key — an admin has to be able to review what they
 * authored. Only ever reach the client through admin-role-gated routes (see SecurityConfig's
 * adminFilterChain, which 404s any non-ADMIN caller before a controller method runs at all).
 */
public record OutcomeDto(Correctness correctness, int scorePoints, String explanation) {

    public static OutcomeDto from(Outcome outcome) {
        if (outcome == null) {
            return null;
        }
        return new OutcomeDto(outcome.getCorrectness(), outcome.getScorePoints(), outcome.getExplanation());
    }
}
