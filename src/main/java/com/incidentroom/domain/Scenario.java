package com.incidentroom.domain;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;
import java.util.List;

/**
 * The root content object. Denormalized on purpose — topology, actions, and
 * telemetry are always
 * read together and rarely updated piecemeal, so embedding them avoids the
 * join-heavy patterns
 * that don't fit a document DB (see the backend architecture doc §5.2 for the
 * full rationale).
 *
 * `actions` holds BOTH investigate and remediate actions (discriminated by
 * `kind`) — the outcome
 * data on remediate actions is the "answer key" and must never reach the public
 * read API; that's
 * enforced in the DTO layer (ScenarioDetailDto), not here — this document is
 * the full authored
 * truth, server-side only.
 */
@Document(collection = "scenarios")
@CompoundIndex(name = "published_difficulty_idx", def = "{'isPublished': 1, 'difficulty': 1}")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Scenario {

    @Id
    private String id;

    @Indexed(unique = true)
    private String slug;

    private String title;
    private Difficulty difficulty;
    private String rootCauseClass;
    private String symptomSummary;
    private int durationTargetSeconds;
    private boolean isPublished;

    private Topology topology;
    private List<ActionDefinition> actions;
    private List<TelemetryEvent> telemetry;
    private String postmortemMarkdown;
    private ScenarioSeo seo;

    private Instant createdAt;
    private Instant updatedAt;

    /**
     * ISO date (yyyy-MM-dd, UTC) on which this scenario is the designated "daily
     * incident".
     * Null on the vast majority of scenarios — the sparse index means those
     * documents are not
     * indexed at all, keeping the index small. Set/cleared via the admin scheduling
     * endpoint.
     */
    @Indexed(sparse = true)
    private String dailyDate;
}
