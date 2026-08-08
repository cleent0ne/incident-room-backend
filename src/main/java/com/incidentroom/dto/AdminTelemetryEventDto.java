package com.incidentroom.dto;

import com.incidentroom.domain.TelemetryEvent;
import com.incidentroom.domain.TelemetryType;

import java.util.Map;

/** Admin counterpart to {@link TelemetryEventDto} — includes `revealedBy`, since an admin
 *  reviewing a scenario needs to see the gating structure (which action reveals which event),
 *  not just the ambient/ungated subset a player sees before starting an attempt. */
public record AdminTelemetryEventDto(int t, TelemetryType type, String source, String revealedBy, Map<String, Object> payload) {

    public static AdminTelemetryEventDto from(TelemetryEvent event) {
        return new AdminTelemetryEventDto(event.getT(), event.getType(), event.getSource(),
                event.getRevealedBy(), event.getPayload());
    }
}
