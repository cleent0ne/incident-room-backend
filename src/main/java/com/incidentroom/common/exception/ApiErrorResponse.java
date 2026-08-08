package com.incidentroom.common.exception;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;
import java.util.List;

/**
 * Every error response in the API has this exact shape. Deliberately generic —
 * never includes stack traces, internal class names, or raw exception messages,
 * since those are an information leak to a caller who may be an attacker probing the API.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ApiErrorResponse(
        String code,
        String message,
        Instant timestamp,
        String correlationId,
        List<FieldError> fieldErrors
) {
    public record FieldError(String field, String message) {}

    public static ApiErrorResponse of(String code, String message, String correlationId) {
        return new ApiErrorResponse(code, message, Instant.now(), correlationId, null);
    }

    public static ApiErrorResponse ofFieldErrors(String correlationId, List<FieldError> fieldErrors) {
        return new ApiErrorResponse("VALIDATION_FAILED", "One or more fields are invalid.",
                Instant.now(), correlationId, fieldErrors);
    }
}
