package com.incidentroom.service;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.incidentroom.common.exception.BadRequestException;
import com.incidentroom.domain.ActionDefinition;
import com.incidentroom.domain.ActionKind;
import com.incidentroom.domain.Correctness;
import com.incidentroom.domain.Difficulty;
import com.incidentroom.domain.Outcome;
import com.incidentroom.domain.Scenario;
import com.incidentroom.domain.ScenarioSeo;
import com.incidentroom.domain.TelemetryEvent;
import com.incidentroom.domain.TelemetryType;
import com.incidentroom.domain.Topology;
import com.incidentroom.domain.TopologyEdge;
import com.incidentroom.domain.TopologyNode;
import lombok.Getter;
import lombok.Setter;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Scenarios are authored as YAML, not code — see backend architecture doc §6 for the full worked
 * example this schema mirrors 1:1. This service is the only place that YAML shape is known; once
 * parsed, everything downstream deals exclusively with the {@link Scenario} domain document.
 *
 * Import always succeeds and persists as a draft (isPublished stays false/unchanged — see
 * ScenarioService.upsertScenario) even if content-validation would fail; validation is enforced
 * as a hard gate only at publish time (AdminScenarioController), so an author can iterate on a
 * half-finished scenario across multiple imports without fighting the validator until they're
 * actually ready to go live. The import response still surfaces validation errors as advisory
 * information either way.
 */
@Service
public class ScenarioYamlImportService {

    private final ObjectMapper yamlMapper;
    private final ScenarioService scenarioService;
    private final ScenarioValidationService validationService;

    public ScenarioYamlImportService(ScenarioService scenarioService, ScenarioValidationService validationService) {
        this.yamlMapper = new ObjectMapper(new YAMLFactory())
                .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        this.scenarioService = scenarioService;
        this.validationService = validationService;
    }

    public record ImportResult(Scenario scenario, List<String> validationWarnings) {}

    public ImportResult importYaml(String yamlContent) {
        ScenarioYaml parsed;
        try {
            parsed = yamlMapper.readValue(yamlContent, ScenarioYaml.class);
        } catch (IOException e) {
            throw new BadRequestException("Could not parse scenario YAML: " + e.getMessage());
        }

        Scenario scenario = toDomain(parsed);
        Scenario saved = scenarioService.upsertScenario(scenario);
        List<String> warnings = validationService.validate(saved);

        return new ImportResult(saved, warnings);
    }

    private Scenario toDomain(ScenarioYaml y) {
        requireNonBlank(y.slug, "slug");
        requireNonBlank(y.title, "title");

        Topology topology = null;
        if (y.topology != null) {
            List<TopologyNode> nodes = y.topology.nodes == null ? List.of() : y.topology.nodes.stream()
                    .map(n -> TopologyNode.builder().id(n.id).type(n.type).label(n.label).build())
                    .toList();
            List<TopologyEdge> edges = y.topology.edges == null ? List.of() : y.topology.edges.stream()
                    .map(e -> TopologyEdge.builder().from(e.from).to(e.to).build())
                    .toList();
            topology = Topology.builder().nodes(nodes).edges(edges).build();
        }

        List<ActionDefinition> actions = new ArrayList<>();
        if (y.actions != null) {
            if (y.actions.investigate != null) {
                y.actions.investigate.forEach(a -> actions.add(
                        ActionDefinition.builder()
                                .key(a.key)
                                .kind(ActionKind.INVESTIGATE)
                                .label(a.label)
                                .description(a.description)
                                .costSeconds(a.costSeconds)
                                .build()));
            }
            if (y.actions.remediate != null) {
                y.actions.remediate.forEach(a -> actions.add(
                        ActionDefinition.builder()
                                .key(a.key)
                                .kind(ActionKind.REMEDIATE)
                                .label(a.label)
                                .description(a.description)
                                .outcome(Outcome.builder()
                                        .correctness(parseCorrectness(a.correctness))
                                        .scorePoints(a.scorePoints)
                                        .explanation(a.explanation)
                                        .build())
                                .build()));
            }
        }

        List<TelemetryEvent> telemetry = y.telemetry == null ? List.of() : y.telemetry.stream()
                .map(t -> TelemetryEvent.builder()
                        .t(t.t)
                        .type(parseTelemetryType(t.type))
                        .source(t.source)
                        .revealedBy(t.revealedBy)
                        .payload(t.payload)
                        .build())
                .toList();

        ScenarioSeo seo = null;
        if (y.seo != null) {
            seo = ScenarioSeo.builder()
                    .metaTitle(y.seo.metaTitle)
                    .metaDescription(y.seo.metaDescription)
                    .ogImagePath(y.seo.ogImagePath)
                    .build();
        }

        return Scenario.builder()
                .slug(y.slug.trim().toLowerCase(Locale.ROOT))
                .title(y.title)
                .difficulty(parseDifficulty(y.difficulty))
                .rootCauseClass(y.rootCauseClass)
                .symptomSummary(y.symptomSummary)
                .durationTargetSeconds(y.durationTargetSeconds)
                .topology(topology)
                .actions(actions)
                .telemetry(telemetry)
                .postmortemMarkdown(y.postmortem)
                .seo(seo)
                .build();
    }

    private void requireNonBlank(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new BadRequestException("Scenario YAML is missing required field: " + fieldName);
        }
    }

    private Difficulty parseDifficulty(String value) {
        if (value == null) {
            throw new BadRequestException("Scenario YAML is missing required field: difficulty");
        }
        try {
            return Difficulty.valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new BadRequestException("Unknown difficulty '" + value + "' — expected easy/medium/hard");
        }
    }

    private Correctness parseCorrectness(String value) {
        if (value == null) {
            throw new BadRequestException("A remediate action is missing 'correctness'");
        }
        try {
            return Correctness.valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new BadRequestException("Unknown correctness '" + value
                    + "' — expected correct/partially_correct/incorrect/harmful");
        }
    }

    private TelemetryType parseTelemetryType(String value) {
        if (value == null) {
            throw new BadRequestException("A telemetry event is missing 'type'");
        }
        try {
            return TelemetryType.valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new BadRequestException("Unknown telemetry type '" + value
                    + "' — expected log/metric_point/trace_span");
        }
    }

    // ---- YAML shape (Jackson-mapped, snake_case) — mirrors backend architecture doc §6 exactly ----

    @Getter @Setter
    static class ScenarioYaml {
        String slug;
        String title;
        String difficulty;
        @JsonProperty("root_cause_class") String rootCauseClass;
        @JsonProperty("symptom_summary") String symptomSummary;
        @JsonProperty("duration_target_seconds") int durationTargetSeconds;
        SeoYaml seo;
        TopologyYaml topology;
        ActionsYaml actions;
        List<TelemetryYaml> telemetry;
        String postmortem;
    }

    @Getter @Setter
    static class SeoYaml {
        @JsonProperty("meta_title") String metaTitle;
        @JsonProperty("meta_description") String metaDescription;
        @JsonProperty("og_image_path") String ogImagePath;
    }

    @Getter @Setter
    static class TopologyYaml {
        List<NodeYaml> nodes;
        List<EdgeYaml> edges;
    }

    @Getter @Setter
    static class NodeYaml {
        String id;
        String type;
        String label;
    }

    @Getter @Setter
    static class EdgeYaml {
        String from;
        String to;
    }

    @Getter @Setter
    static class ActionsYaml {
        List<InvestigateActionYaml> investigate;
        List<RemediateActionYaml> remediate;
    }

    @Getter @Setter
    static class InvestigateActionYaml {
        String key;
        String label;
        String description;
        @JsonProperty("cost_seconds") Integer costSeconds;
    }

    @Getter @Setter
    static class RemediateActionYaml {
        String key;
        String label;
        String description;
        String correctness;
        @JsonProperty("score_points") int scorePoints;
        String explanation;
    }

    @Getter @Setter
    static class TelemetryYaml {
        int t;
        String type;
        String source;
        @JsonProperty("revealed_by") String revealedBy;
        Map<String, Object> payload;
    }
}
