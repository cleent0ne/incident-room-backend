package com.incidentroom.dto;

import java.util.List;

public record TakeActionResponse(List<TelemetryEventDto> revealedTelemetry, int costSeconds, int elapsedSeconds) {}
