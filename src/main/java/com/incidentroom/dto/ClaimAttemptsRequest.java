package com.incidentroom.dto;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * Each entry is the opaque capabilityToken returned by POST /attempts at creation time.
 * Using the token, not the MongoDB ObjectId, is what prevents an attacker from guessing
 * other players' attempt IDs and stealing their scores — see AttemptService.claim() and
 * Attempt.capabilityToken for the full rationale.
 */
public record ClaimAttemptsRequest(
        @NotEmpty @Size(max = 20, message = "At most 20 tokens per call.") List<String> capabilityTokens
) {}
