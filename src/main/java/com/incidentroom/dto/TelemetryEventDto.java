package com.incidentroom.dto;

import com.incidentroom.domain.TelemetryEvent;
import com.incidentroom.domain.TelemetryType;

import java.util.Map;

public record TelemetryEventDto(int t, TelemetryType type, String source, Map<String, Object> payload) {

    public static TelemetryEventDto from(TelemetryEvent event) {
        return new TelemetryEventDto(event.getT(), event.getType(), event.getSource(), event.getPayload());
    }
}
