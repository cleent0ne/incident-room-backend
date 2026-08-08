package com.incidentroom.service;

import com.incidentroom.domain.ActionDefinition;
import com.incidentroom.domain.ActionKind;
import com.incidentroom.domain.Correctness;
import com.incidentroom.domain.Outcome;
import com.incidentroom.domain.Scenario;
import com.incidentroom.domain.ScenarioSeo;
import com.incidentroom.domain.TelemetryEvent;
import com.incidentroom.domain.TelemetryType;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ScenarioValidationServiceTest {

    private final ScenarioValidationService validator = new ScenarioValidationService();

    private Scenario.ScenarioBuilder validScenarioBuilder() {
        ActionDefinition investigate = ActionDefinition.builder()
                .key("check_logs").kind(ActionKind.INVESTIGATE).label("Check logs").costSeconds(10).build();

        ActionDefinition correctRemediation = ActionDefinition.builder()
                .key("fix_it").kind(ActionKind.REMEDIATE).label("Fix it")
                .outcome(Outcome.builder().correctness(Correctness.CORRECT).scorePoints(100).explanation("Because reasons").build())
                .build();

        return Scenario.builder()
                .slug("valid-scenario-slug")
                .title("A Valid Scenario")
                .actions(List.of(investigate, correctRemediation))
                .telemetry(List.of(
                        TelemetryEvent.builder().t(0).type(TelemetryType.LOG).source("svc")
                                .revealedBy("check_logs").payload(java.util.Map.of("message", "hi")).build()))
                .seo(ScenarioSeo.builder()
                        .metaTitle("A short, valid title")
                        .metaDescription("A short, valid description under the length limit.")
                        .build());
    }

    @Test
    void validScenario_passesWithNoErrors() {
        Scenario scenario = validScenarioBuilder().build();
        assertThat(validator.validate(scenario)).isEmpty();
        assertThat(validator.isValid(scenario)).isTrue();
    }

    @Test
    void invalidSlug_isRejected() {
        Scenario scenario = validScenarioBuilder().slug("Not A Valid Slug!").build();
        assertThat(validator.validate(scenario)).anyMatch(e -> e.contains("slug must be lowercase"));
    }

    @Test
    void danglingRevealedByReference_isRejected() {
        Scenario scenario = validScenarioBuilder()
                .telemetry(List.of(
                        TelemetryEvent.builder().t(0).type(TelemetryType.LOG).source("svc")
                                .revealedBy("this_action_does_not_exist")
                                .payload(java.util.Map.of("message", "hi")).build()))
                .build();

        assertThat(validator.validate(scenario)).anyMatch(e -> e.contains("does not match any INVESTIGATE action key"));
    }

    @Test
    void noCorrectRemediation_isRejected() {
        ActionDefinition onlyIncorrect = ActionDefinition.builder()
                .key("wrong_fix").kind(ActionKind.REMEDIATE).label("Wrong fix")
                .outcome(Outcome.builder().correctness(Correctness.INCORRECT).scorePoints(10).explanation("Nope").build())
                .build();

        Scenario scenario = validScenarioBuilder().actions(List.of(onlyIncorrect)).build();

        assertThat(validator.validate(scenario)).anyMatch(e -> e.contains("at least one REMEDIATE action with correctness=CORRECT"));
    }

    @Test
    void missingSeoMetadata_isRejected() {
        Scenario scenario = validScenarioBuilder().seo(null).build();
        assertThat(validator.validate(scenario)).anyMatch(e -> e.contains("seo metadata is required"));
    }

    @Test
    void metaTitleTooLong_isRejected() {
        String tooLong = "a".repeat(100);
        Scenario scenario = validScenarioBuilder()
                .seo(ScenarioSeo.builder().metaTitle(tooLong).metaDescription("fine").build())
                .build();

        assertThat(validator.validate(scenario)).anyMatch(e -> e.contains("seo.metaTitle exceeds"));
    }

    @Test
    void remediationMissingExplanation_isRejected() {
        ActionDefinition noExplanation = ActionDefinition.builder()
                .key("fix_it").kind(ActionKind.REMEDIATE).label("Fix it")
                .outcome(Outcome.builder().correctness(Correctness.CORRECT).scorePoints(100).explanation("").build())
                .build();

        Scenario scenario = validScenarioBuilder().actions(List.of(noExplanation)).build();

        assertThat(validator.validate(scenario)).anyMatch(e -> e.contains("missing an explanation"));
    }
}
