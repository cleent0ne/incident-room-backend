package com.incidentroom.controller;

import com.incidentroom.dto.AttemptSummaryDto;
import com.incidentroom.dto.UpdateProfileRequest;
import com.incidentroom.dto.UserResponse;
import com.incidentroom.dto.UserStatsDto;
import com.incidentroom.service.AttemptService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.data.domain.Page;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "User Profile", description = "Authenticated user account statistics and attempt history")
@RestController
@RequestMapping("/api/v1/users")
public class UserController {

    private final AttemptService attemptService;
    private final com.incidentroom.service.AuthService authService;

    public UserController(AttemptService attemptService, com.incidentroom.service.AuthService authService) {
        this.attemptService = attemptService;
        this.authService = authService;
    }

    @Operation(summary = "Get user attempt history", description = "Returns a paginated history of all incident attempts played by the authenticated user.")
    @SecurityRequirement(name = "cookieAuth")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Paginated list of user attempt summaries"),
            @ApiResponse(responseCode = "401", description = "Unauthenticated")
    })
    @GetMapping("/me/attempts")
    public Page<AttemptSummaryDto> myAttempts(
            Authentication authentication,
            @Parameter(description = "Zero-indexed page index") @RequestParam(defaultValue = "0") int page,
            @Parameter(description = "Page size (capped at 50 max)") @RequestParam(defaultValue = "20") int size) {
        if (authentication == null || !authentication.isAuthenticated()) {
            throw new com.incidentroom.common.exception.InvalidCredentialsException();
        }
        int cappedSize = Math.min(size, 50);
        return attemptService.getHistoryForUser(authentication.getName(), page, cappedSize);
    }

    @Operation(summary = "Get user stats summary", description = "Total completed incidents and average score across the authenticated user's full history — computed server-side via aggregation, not limited to one page of attempts.")
    @SecurityRequirement(name = "cookieAuth")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "User stats summary"),
            @ApiResponse(responseCode = "401", description = "Unauthenticated")
    })
    @GetMapping("/me/stats")
    public UserStatsDto myStats(Authentication authentication) {
        if (authentication == null || !authentication.isAuthenticated()) {
            throw new com.incidentroom.common.exception.InvalidCredentialsException();
        }
        return attemptService.getStatsForUser(authentication.getName());
    }

    @Operation(summary = "Update profile", description = "Updates the authenticated user's display name.")
    @SecurityRequirement(name = "cookieAuth")
    @SecurityRequirement(name = "csrfHeader")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Updated user profile"),
            @ApiResponse(responseCode = "400", description = "Validation failed"),
            @ApiResponse(responseCode = "401", description = "Unauthenticated")
    })
    @PatchMapping("/me")
    public ResponseEntity<UserResponse> updateProfile(
            Authentication authentication,
            @Valid @RequestBody UpdateProfileRequest request) {
        if (authentication == null || !authentication.isAuthenticated()) {
            throw new com.incidentroom.common.exception.InvalidCredentialsException();
        }
        UserResponse updated = authService.updateDisplayName(authentication.getName(), request.displayName().trim());
        return ResponseEntity.ok(updated);
    }


    @Operation(summary = "Export user account data (GDPR Art. 15 & 20)", description = "Returns a complete JSON archive of the authenticated user's account profile, streak data, attempt history, and leaderboard records.")
    @SecurityRequirement(name = "cookieAuth")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Complete user data export JSON payload"),
            @ApiResponse(responseCode = "401", description = "Unauthenticated")
    })
    @GetMapping("/me/export")
    public org.springframework.http.ResponseEntity<com.incidentroom.dto.UserExportDataDto> exportMyData(Authentication authentication) {
        if (authentication == null || !authentication.isAuthenticated()) {
            throw new com.incidentroom.common.exception.InvalidCredentialsException();
        }
        com.incidentroom.dto.UserExportDataDto exportData = authService.exportUserData(authentication.getName(), attemptService);
        return org.springframework.http.ResponseEntity.ok()
                .header(org.springframework.http.HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"incidentroom-data-export.json\"")
                .body(exportData);
    }

    @Operation(summary = "Delete user account (GDPR Article 17)", description = "Permanently deletes the authenticated user account and all linked data (attempts, refresh tokens, leaderboard entries, verification codes).")
    @SecurityRequirement(name = "cookieAuth")
    @SecurityRequirement(name = "csrfHeader")
    @ApiResponses({
            @ApiResponse(responseCode = "204", description = "Account permanently deleted and cookies cleared"),
            @ApiResponse(responseCode = "401", description = "Unauthenticated")
    })
    @org.springframework.web.bind.annotation.DeleteMapping("/me")
    public org.springframework.http.ResponseEntity<Void> deleteAccount(
            Authentication authentication,
            jakarta.servlet.http.HttpServletResponse httpResponse) {
        if (authentication == null || !authentication.isAuthenticated()) {
            throw new com.incidentroom.common.exception.InvalidCredentialsException();
        }
        authService.deleteAccount(authentication.getName(), httpResponse);
        return org.springframework.http.ResponseEntity.noContent().build();
    }
}