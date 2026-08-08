package com.incidentroom.domain;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.Map;

@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TelemetryEvent {
    /** Offset in seconds from the start of the incident — purely for authoring/ordering context. */
    private int t;
    private TelemetryType type;
    private String source;

    /** Null = ambient, always visible from t=0. Non-null = only revealed after that investigate
     *  action key is taken (Phase 3's AttemptService is what enforces this gating). */
    private String revealedBy;

    /** Shape depends on `type`: {level, message} for LOG, {name, value} for METRIC_POINT, etc. —
     *  deliberately a flexible map rather than a rigid type, since telemetry payloads are
     *  authored content, not a fixed schema the backend needs to validate structurally. */
    private Map<String, Object> payload;
}
