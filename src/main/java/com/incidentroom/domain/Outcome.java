package com.incidentroom.domain;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Only present on REMEDIATE actions. NEVER serialized to the public scenario-detail API —
 * this is the "answer key" (see dto.ScenarioDetailDto, which strips it explicitly) and is only
 * read server-side when an attempt is resolved (Phase 3).
 */
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Outcome {
    private Correctness correctness;
    private int scorePoints;
    private String explanation;
}
