package com.incidentroom.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.incidentroom.domain.Scenario;
import com.incidentroom.repository.ScenarioRepository;

import java.time.Instant;

/**
 * CI validation gate for seed-data/*.yaml files.
 *
 * Reads every YAML file from the seed-data/ directory (relative to project root, matching where
 * authors drop new scenario files), parses each one through ScenarioYamlImportService, and
 * asserts that validation produces no warnings. This catches authoring errors at CI time rather
 * than at manual import time — a broken scenario can't reach main without an explicit signal.
 *
 * If no seed files are found, the test is skipped (assumption failure rather than error),
 * so a fresh repo with an empty seed-data/ directory doesn't fail CI.
 */
@ExtendWith(MockitoExtension.class)
class SeedDataValidationTest {

    @Mock private ScenarioRepository scenarioRepository;
    @Mock private org.springframework.data.mongodb.core.MongoTemplate mongoTemplate;

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
                if (s.getId() == null) s.setId("seed-test-id");
                if (s.getCreatedAt() == null) s.setCreatedAt(Instant.now());
                s.setUpdatedAt(Instant.now());
            }
            return s;
        });
    }

    @Test
    void allSeedScenarios_passValidation() throws IOException {
        Path seedDir = Path.of("seed-data");
        assumeTrue(Files.exists(seedDir) && Files.isDirectory(seedDir),
                "seed-data/ directory not found — skipping seed validation");

        List<Path> yamlFiles;
        try (var stream = Files.list(seedDir)) {
            yamlFiles = stream
                    .filter(p -> p.toString().endsWith(".yaml") || p.toString().endsWith(".yml"))
                    .sorted()
                    .toList();
        }

        assumeTrue(!yamlFiles.isEmpty(), "No YAML files in seed-data/ — skipping");

        List<String> failures = new ArrayList<>();

        for (Path yamlPath : yamlFiles) {
            String content = Files.readString(yamlPath);
            try {
                ScenarioYamlImportService.ImportResult result = importService.importYaml(content);
                if (!result.validationWarnings().isEmpty()) {
                    failures.add(yamlPath.getFileName() + ": " + result.validationWarnings());
                }
            } catch (Exception e) {
                failures.add(yamlPath.getFileName() + " failed to parse: " + e.getMessage());
            }
        }

        assertThat(failures)
                .as("Seed data validation failures — fix these before merging:\n%s",
                        String.join("\n", failures))
                .isEmpty();
    }
}
