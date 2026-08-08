package com.incidentroom.domain;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ActionDefinition {
    private String key;
    private ActionKind kind;
    private String label;
    private String description;
    private Integer costSeconds;

    /** Null for INVESTIGATE actions; always present for REMEDIATE actions. */
    private Outcome outcome;
}
