package com.incidentroom.controller;

import com.incidentroom.common.exception.InvalidCredentialsException;
import com.incidentroom.dto.AttemptStateResponse;
import com.incidentroom.dto.ClaimAttemptsRequest;
import com.incidentroom.dto.ClaimAttemptsResponse;
import com.incidentroom.dto.ResolveAttemptRequest;
import com.incidentroom.dto.ResolveAttemptResponse;
import com.incidentroom.dto.SaveReflectionRequest;
import com.incidentroom.dto.StartAttemptRequest;
import com.incidentroom.dto.TakeActionRequest;
import com.incidentroom.dto.TakeActionResponse;
import com.incidentroom.security.CurrentUserResolver;
import com.incidentroom.service.AttemptService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Attempts", description = "Incident gameplay loop: start attempt, investigate actions, telemetry feed, reflection, resolve, debrief, and claim")
@RestController
@RequestMapping("/api/v1/attempts")
public class AttemptController {

    public static final String CAPABILITY_TOKEN_HEADER = "X-Capability-Token";

    private final AttemptService attemptService;
    private final CurrentUserResolver currentUserResolver;

    public AttemptController(AttemptService attemptService, CurrentUserResolver currentUserResolver) {
        this.attemptService = attemptService;
        this.currentUserResolver = currentUserResolver;
    }

    @Operation(summary = "Start a new incident attempt", description = "Creates a new attempt for a scenario. Supports both anonymous players (returns capabilityToken) and authenticated users.")
    @SecurityRequirement(name = "csrfHeader")
    @SecurityRequirement(name = "cookieAuth")
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "Attempt created successfully"),
            @ApiResponse(responseCode = "404", description = "Scenario not found or not published"),
            @ApiResponse(responseCode = "429", description = "Rate limit exceeded (30 / minute / IP)")
    })
    @PostMapping
    public ResponseEntity<AttemptStateResponse> start(@Valid @RequestBody StartAttemptRequest request,
                                                       Authentication authentication) {
        AttemptStateResponse response = attemptService.start(request.scenarioSlug(),
                currentUserResolver.resolve(authentication));
        return ResponseEntity.status(201).body(response);
    }

    @Operation(summary = "Get current attempt state", description = "Fetches current state of an in-progress attempt to hydrate the play screen after page reload.")
    @SecurityRequirement(name = "capabilityTokenHeader")
    @SecurityRequirement(name = "cookieAuth")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Attempt state response"),
            @ApiResponse(responseCode = "401", description = "Missing or invalid capability token / auth cookie"),
            @ApiResponse(responseCode = "403", description = "Attempt belongs to another user"),
            @ApiResponse(responseCode = "404", description = "Attempt not found or expired")
    })
    @GetMapping("/{id}")
    public AttemptStateResponse getState(
            @Parameter(description = "Attempt ID") @PathVariable String id,
            @Parameter(description = "Capability token (required for anonymous attempts)") @RequestHeader(value = CAPABILITY_TOKEN_HEADER, required = false) String capabilityToken,
            Authentication authentication) {
        return attemptService.getState(id, currentUserResolver.resolve(authentication), capabilityToken);
    }

    @Operation(summary = "Get resolved attempt debrief result", description = "Recovers the full debrief result for an already-completed attempt. Returns 409 Conflict if not yet resolved.")
    @SecurityRequirement(name = "capabilityTokenHeader")
    @SecurityRequirement(name = "cookieAuth")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Debrief result response"),
            @ApiResponse(responseCode = "401", description = "Missing or invalid capability token / auth cookie"),
            @ApiResponse(responseCode = "403", description = "Attempt belongs to another user"),
            @ApiResponse(responseCode = "404", description = "Attempt not found"),
            @ApiResponse(responseCode = "409", description = "Attempt exists but has not been resolved yet")
    })
    @GetMapping("/{id}/result")
    public ResolveAttemptResponse getResult(
            @Parameter(description = "Attempt ID") @PathVariable String id,
            @Parameter(description = "Capability token (required for anonymous attempts)") @RequestHeader(value = CAPABILITY_TOKEN_HEADER, required = false) String capabilityToken,
            Authentication authentication) {
        return attemptService.getResult(id, currentUserResolver.resolve(authentication), capabilityToken);
    }

    @Operation(summary = "Take investigative action", description = "Runs a diagnostic action (INVESTIGATE kind) to reveal additional telemetry signals.")
    @SecurityRequirement(name = "capabilityTokenHeader")
    @SecurityRequirement(name = "cookieAuth")
    @SecurityRequirement(name = "csrfHeader")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Newly revealed telemetry response"),
            @ApiResponse(responseCode = "400", description = "Invalid action key or action is REMEDIATE kind"),
            @ApiResponse(responseCode = "401", description = "Missing or invalid capability token"),
            @ApiResponse(responseCode = "403", description = "Attempt belongs to another user"),
            @ApiResponse(responseCode = "404", description = "Attempt or action key not found"),
            @ApiResponse(responseCode = "429", description = "Rate limit exceeded (60 / minute / IP)")
    })
    @PostMapping("/{id}/actions")
    public TakeActionResponse takeAction(
            @Parameter(description = "Attempt ID") @PathVariable String id,
            @Valid @RequestBody TakeActionRequest request,
            @Parameter(description = "Capability token (required for anonymous attempts)") @RequestHeader(value = CAPABILITY_TOKEN_HEADER, required = false) String capabilityToken,
            Authentication authentication) {
        return attemptService.takeInvestigativeAction(id, request.actionKey(),
                currentUserResolver.resolve(authentication), capabilityToken);
    }

    @Operation(summary = "Resolve incident attempt", description = "Commits a remediation action (REMEDIATE kind) to resolve the incident and get final debrief & score.")
    @SecurityRequirement(name = "capabilityTokenHeader")
    @SecurityRequirement(name = "cookieAuth")
    @SecurityRequirement(name = "csrfHeader")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Incident resolved — returns full debrief score breakdown"),
            @ApiResponse(responseCode = "400", description = "Invalid action key, not REMEDIATE kind, or attempt already resolved"),
            @ApiResponse(responseCode = "401", description = "Missing or invalid capability token"),
            @ApiResponse(responseCode = "403", description = "Attempt belongs to another user"),
            @ApiResponse(responseCode = "404", description = "Attempt or action key not found"),
            @ApiResponse(responseCode = "429", description = "Rate limit exceeded (60 / minute / IP)")
    })
    @PostMapping("/{id}/resolve")
    public ResolveAttemptResponse resolve(
            @Parameter(description = "Attempt ID") @PathVariable String id,
            @Valid @RequestBody ResolveAttemptRequest request,
            @Parameter(description = "Capability token (required for anonymous attempts)") @RequestHeader(value = CAPABILITY_TOKEN_HEADER, required = false) String capabilityToken,
            Authentication authentication) {
        return attemptService.resolve(id, request.actionKey(),
                currentUserResolver.resolve(authentication), capabilityToken);
    }

    @Operation(summary = "Save working hypothesis / reflection", description = "Saves the player's free-text hypothesis before committing to a remediation.")
    @SecurityRequirement(name = "capabilityTokenHeader")
    @SecurityRequirement(name = "cookieAuth")
    @SecurityRequirement(name = "csrfHeader")
    @ApiResponses({
            @ApiResponse(responseCode = "204", description = "Reflection saved"),
            @ApiResponse(responseCode = "400", description = "Text exceeds 2000 characters limit"),
            @ApiResponse(responseCode = "401", description = "Missing or invalid capability token"),
            @ApiResponse(responseCode = "429", description = "Rate limit exceeded (20 / minute / IP)")
    })
    @PatchMapping("/{id}/reflection")
    public ResponseEntity<Void> saveReflection(
            @Parameter(description = "Attempt ID") @PathVariable String id,
            @Valid @RequestBody SaveReflectionRequest request,
            @Parameter(description = "Capability token (required for anonymous attempts)") @RequestHeader(value = CAPABILITY_TOKEN_HEADER, required = false) String capabilityToken,
            Authentication authentication) {
        attemptService.saveReflection(id, request.text(),
                currentUserResolver.resolve(authentication), capabilityToken);
        return ResponseEntity.noContent().build();
    }

    @Operation(summary = "Claim anonymous attempts", description = "Links anonymous attempts played prior to account creation to the now-authenticated user account.")
    @SecurityRequirement(name = "cookieAuth")
    @SecurityRequirement(name = "csrfHeader")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Claim result response — count of newly linked attempts"),
            @ApiResponse(responseCode = "400", description = "Empty token list or list size exceeds 20 max"),
            @ApiResponse(responseCode = "401", description = "Not authenticated"),
            @ApiResponse(responseCode = "429", description = "Rate limit exceeded (5 / minute / IP)")
    })
    @PostMapping("/claim")
    public ClaimAttemptsResponse claim(@Valid @RequestBody ClaimAttemptsRequest request,
                                        Authentication authentication) {
        String userId = currentUserResolver.resolve(authentication);
        if (userId == null) {
            throw new InvalidCredentialsException();
        }
        return attemptService.claim(request.capabilityTokens(), userId);
    }
}