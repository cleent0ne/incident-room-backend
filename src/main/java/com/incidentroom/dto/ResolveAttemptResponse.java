package com.incidentroom.dto;

import com.incidentroom.domain.Correctness;

/**
 * scorePoints/timeBonus/finalScore are split out (not just a single total) deliberately — the
 * debrief UI leads with *why* a choice was right or wrong before it leads with the number (see
 * the UI/UX design spec §5), and showing the breakdown is part of what makes that possible.
 */
public record ResolveAttemptResponse(
        Correctness correctness,
        int scorePoints,
        int timeBonus,
        int finalScore,
        String explanation,
        String postmortem,
        int elapsedSeconds,
        Integer percentile,
        String reflectionText,
        String scenarioSlug,
        String scenarioTitle
) {
    public ResolveAttemptResponse(
            Correctness correctness,
            int scorePoints,
            int timeBonus,
            int finalScore,
            String explanation,
            String postmortem,
            int elapsedSeconds) {
        this(correctness, scorePoints, timeBonus, finalScore, explanation, postmortem, elapsedSeconds, null, null, null, null);
    }
}
