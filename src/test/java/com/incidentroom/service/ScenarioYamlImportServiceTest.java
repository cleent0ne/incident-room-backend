package com.incidentroom.service;

import com.incidentroom.common.exception.BadRequestException;
import com.incidentroom.domain.Scenario;
import com.incidentroom.repository.ScenarioRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.mongodb.core.MongoTemplate;

import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

/**
 * Unit tests for ScenarioYamlImportService covering edge cases:
 * malformed YAML, unknown fields, missing required blocks, and upsert behaviour.
 */
@ExtendWith(MockitoExtension.class)
class ScenarioYamlImportServiceTest {

    @Mock private ScenarioRepository scenarioRepository;
    @Mock private MongoTemplate mongoTemplate;

    private ScenarioYamlImportService importService;

    @BeforeEach
    void setUp() {
        ScenarioService scenarioService = new ScenarioService(scenarioRepository, mongoTemplate);
        ScenarioValidationService validationService = new ScenarioValidationService();
        importService = new ScenarioYamlImportService(scenarioService, validationService);

        lenient().when(scenarioRepository.findBySlug(any())).thenReturn(Optional.empty());
        lenient().when(scenarioRepository.save(any())).thenAnswer(inv -> {
            Scenario s = inv.getArgument(0);
            if (s != null) {
                if (s.getId() == null) s.setId("generated-id");
                if (s.getCreatedAt() == null) s.setCreatedAt(Instant.now());
                s.setUpdatedAt(Instant.now());
            }
            return s;
        });
    }

    // ── error paths ───────────────────────────────────────────────────────────

    @Test
    void import_malformedYaml_throwsBadRequestException() {
        String malformed = "key: [unclosed bracket\nnested: {also: broken";

        assertThatThrownBy(() -> importService.importYaml(malformed))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("YAML");
    }

    @Test
    void import_unknownFields_parsesCleanly() {
        String yaml = minimalValidYaml("unknown-fields-test") + "\nunknown_future_field: some_value\n";

        ScenarioYamlImportService.ImportResult result = importService.importYaml(yaml);

        assertThat(result.scenario().getSlug()).isEqualTo("unknown-fields-test");
    }

    // ── validation advisory warnings ─────────────────────────────────────────

    @Test
    void import_minimalValidScenario_noValidationWarnings() {
        ScenarioYamlImportService.ImportResult result = importService.importYaml(minimalValidYaml("valid-scenario"));

        assertThat(result.validationWarnings()).isEmpty();
        assertThat(result.scenario().getSlug()).isEqualTo("valid-scenario");
    }

    @Test
    void import_missingSeoBlock_warningReported() {
        String yaml = """
                slug: no-seo-scenario
                title: "No SEO scenario"
                difficulty: easy
                root_cause_class: bad_deploy
                symptom_summary: "Something failed."
                duration_target_seconds: 120
                actions:
                  remediate:
                    - key: fix-it
                      label: "Fix it"
                      correctness: correct
                      score_points: 100
                      explanation: "Good fix."
                """;

        ScenarioYamlImportService.ImportResult result = importService.importYaml(yaml);

        assertThat(result.validationWarnings())
                .anySatisfy(w -> assertThat(w).containsIgnoringCase("seo"));
    }

    @Test
    void import_noCorrectRemediation_warningReported() {
        String yaml = """
                slug: no-correct-remedy
                title: "No Correct Remedy"
                difficulty: medium
                root_cause_class: bad_deploy
                symptom_summary: "Something failed."
                duration_target_seconds: 120
                seo:
                  meta_title: "No Correct Remedy"
                  meta_description: "A scenario with no correct remediation action for testing purposes."
                actions:
                  remediate:
                    - key: bad-fix
                      label: "Bad Fix"
                      correctness: incorrect
                      score_points: 0
                      explanation: "Not the right approach."
                """;

        ScenarioYamlImportService.ImportResult result = importService.importYaml(yaml);

        assertThat(result.validationWarnings())
                .anySatisfy(w -> assertThat(w).containsIgnoringCase("correct"));
    }

    // ── upsert behaviour ──────────────────────────────────────────────────────

    @Test
    void import_existingSlug_updatesRatherThanDuplicates() {
        Scenario existing = new Scenario();
        existing.setId("existing-id");
        existing.setSlug("existing-scenario");
        existing.setTitle("Old Title");
        existing.setPublished(false);
        existing.setCreatedAt(Instant.now().minusSeconds(3600));
        existing.setUpdatedAt(Instant.now().minusSeconds(3600));

        when(scenarioRepository.findBySlug("existing-scenario")).thenReturn(Optional.of(existing));

        String yaml = minimalValidYaml("existing-scenario").replace("Minimal Valid Scenario", "Updated Title");

        ScenarioYamlImportService.ImportResult result = importService.importYaml(yaml);

        assertThat(result.scenario().getId()).isEqualTo("existing-id");
        assertThat(result.scenario().getTitle()).isEqualTo("Updated Title");
    }

    @Test
    void import_existingPublishedScenario_doesNotUnpublish() {
        Scenario existing = new Scenario();
        existing.setId("pub-id");
        existing.setSlug("published-scenario");
        existing.setTitle("Published");
        existing.setPublished(true);
        existing.setCreatedAt(Instant.now().minusSeconds(3600));
        existing.setUpdatedAt(Instant.now().minusSeconds(3600));

        when(scenarioRepository.findBySlug("published-scenario")).thenReturn(Optional.of(existing));

        ScenarioYamlImportService.ImportResult result =
                importService.importYaml(minimalValidYaml("published-scenario"));

        assertThat(result.scenario().isPublished()).isTrue();
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private String minimalValidYaml(String slug) {
        return """
                slug: %s
                title: "Minimal Valid Scenario"
                difficulty: easy
                root_cause_class: bad_deploy
                symptom_summary: "Something went wrong."
                duration_target_seconds: 120
                seo:
                  meta_title: "Minimal Valid Scenario"
                  meta_description: "A minimal scenario for unit testing the import service."
                actions:
                  remediate:
                    - key: fix-it
                      label: "Fix it"
                      correctness: correct
                      score_points: 100
                      explanation: "That fixed it."
                """.formatted(slug);
    }
}
