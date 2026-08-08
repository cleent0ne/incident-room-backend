package com.incidentroom.dto;

import com.incidentroom.domain.Difficulty;
import com.incidentroom.domain.Scenario;

/** For the catalog/list view — just enough to render a card, no topology/telemetry/actions payload. */
public record ScenarioSummaryDto(
        String slug, String title, Difficulty difficulty, String rootCauseClass, String symptomSummary) {

    public static ScenarioSummaryDto from(Scenario scenario) {
        return new ScenarioSummaryDto(scenario.getSlug(), scenario.getTitle(), scenario.getDifficulty(),
                scenario.getRootCauseClass(), scenario.getSymptomSummary());
    }
}
