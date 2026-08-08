package com.incidentroom.controller;

import com.incidentroom.common.exception.InvalidCredentialsException;
import com.incidentroom.common.exception.NotFoundException;
import com.incidentroom.config.OAuth2ClientRegistrationConfig;
import com.incidentroom.domain.AppUser;
import com.incidentroom.dto.LoginRequest;
import com.incidentroom.dto.RegisterRequest;
import com.incidentroom.dto.ResendVerificationRequest;
import com.incidentroom.dto.UserResponse;
import com.incidentroom.dto.VerifyEmailRequest;
import com.incidentroom.repository.AppUserRepository;
import com.incidentroom.security.CookieService;
import com.incidentroom.service.AuthService;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;

import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.Optional;

@Tag(name = "Auth", description = "Authentication, email verification, session management, and OAuth2 flow")
@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {

    private final AuthService authService;
    private final AppUserRepository userRepository;
    private final OAuth2ClientRegistrationConfig oAuth2ClientRegistrationConfig;

    public AuthController(
            AuthService authService,
            AppUserRepository userRepository,
            OAuth2ClientRegistrationConfig oAuth2ClientRegistrationConfig) {
        this.authService = authService;
        this.userRepository = userRepository;
        this.oAuth2ClientRegistrationConfig = oAuth2ClientRegistrationConfig;
    }

    @Operation(summary = "List available OAuth2 providers",
            description = "Returns which OAuth2 providers (google, github) are currently configured on this " +
                    "deployment, so the frontend can show only working sign-in buttons instead of hardcoding " +
                    "provider availability separately from the backend's actual GOOGLE/GITHUB_OAUTH_* config. " +
                    "An empty list means only email/password sign-in is available. Initiate a flow via " +
                    "GET /oauth2/authorization/{provider} for any ID returned here.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "List of configured provider IDs, e.g. [\"google\", \"github\"]")
    })
    @GetMapping("/oauth-providers")
    public ResponseEntity<Map<String, List<String>>> oauthProviders() {
        return ResponseEntity.ok(Map.of("providers", oAuth2ClientRegistrationConfig.configuredProviderIds()));
    }

    @Operation(summary = "Register new account", description = "Creates a new user account and sends a 6-digit verification code by email.")
    @ApiResponses({
            @ApiResponse(responseCode = "202", description = "Accepted — verification email sent if account is available"),
            @ApiResponse(responseCode = "400", description = "Validation failed for email, password, or displayName"),
            @ApiResponse(responseCode = "429", description = "Rate limit exceeded (5 / hour / IP)")
    })
    @PostMapping("/register")
    public ResponseEntity<Map<String, String>> register(@Valid @RequestBody RegisterRequest request) {
        authService.register(request);
        return ResponseEntity.accepted().body(Map.of(
                "message", "If that email is available, a verification code has been sent."));
    }

    @Operation(summary = "Resend verification code", description = "Resends a new 6-digit email verification code to an unverified user account.")
    @ApiResponses({
            @ApiResponse(responseCode = "202", description = "Accepted — verification code sent if account needs verification"),
            @ApiResponse(responseCode = "400", description = "Validation failed for email"),
            @ApiResponse(responseCode = "429", description = "Rate limit exceeded (3 / hour / IP)")
    })
    @PostMapping("/resend-verification")
    public ResponseEntity<Map<String, String>> resendVerification(@Valid @RequestBody ResendVerificationRequest request) {
        authService.resendVerification(request);
        return ResponseEntity.accepted().body(Map.of("message", "If that account needs verification, a new code has been sent."));
    }

    @Operation(summary = "Verify email address", description = "Verifies the 6-digit code sent by email. Sets HTTP-only auth cookies on success.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Email verified — session established, returns user response"),
            @ApiResponse(responseCode = "400", description = "Validation failed for email or code format"),
            @ApiResponse(responseCode = "401", description = "Invalid or expired verification code")
    })
    @PostMapping("/verify-email")
    public ResponseEntity<UserResponse> verifyEmail(
            @Valid @RequestBody VerifyEmailRequest request,
            HttpServletRequest httpRequest,
            HttpServletResponse httpResponse) {
        return ResponseEntity.ok(authService.verifyEmail(request, httpRequest, httpResponse));
    }

    @Operation(summary = "Log in with email & password", description = "Authenticates user credentials. Sets ir_access, ir_refresh, and ir_csrf cookies on success.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Successfully authenticated"),
            @ApiResponse(responseCode = "401", description = "Incorrect email or password"),
            @ApiResponse(responseCode = "403", description = "Account exists but email is not verified yet"),
            @ApiResponse(responseCode = "429", description = "Rate limit exceeded (10 / minute / IP)")
    })
    @PostMapping("/login")
    public ResponseEntity<UserResponse> login(
            @Valid @RequestBody LoginRequest request,
            HttpServletRequest httpRequest,
            HttpServletResponse httpResponse) {
        return ResponseEntity.ok(authService.login(request, httpRequest, httpResponse));
    }

    @Operation(summary = "Silent refresh access token", description = "Refreshes the JWT access token using the HttpOnly ir_refresh cookie. Rotates the refresh token.")
    @SecurityRequirement(name = "refreshCookieAuth")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Access token successfully refreshed"),
            @ApiResponse(responseCode = "401", description = "Missing, expired, or revoked refresh token"),
            @ApiResponse(responseCode = "429", description = "Rate limit exceeded (30 / hour / IP)")
    })
    @PostMapping("/refresh")
    public ResponseEntity<UserResponse> refresh(HttpServletRequest httpRequest, HttpServletResponse httpResponse) {
        String refreshToken = readCookie(httpRequest, CookieService.REFRESH_COOKIE).orElse(null);
        return ResponseEntity.ok(authService.refresh(refreshToken, httpRequest, httpResponse));
    }

    @Operation(summary = "Log out current session", description = "Revokes the current refresh token and clears all authentication cookies.")
    @SecurityRequirement(name = "refreshCookieAuth")
    @SecurityRequirement(name = "csrfHeader")
    @ApiResponses({
            @ApiResponse(responseCode = "204", description = "Successfully logged out"),
            @ApiResponse(responseCode = "403", description = "Missing or invalid X-CSRF-Token header")
    })
    @PostMapping("/logout")
    public ResponseEntity<Void> logout(HttpServletRequest httpRequest, HttpServletResponse httpResponse) {
        String refreshToken = readCookie(httpRequest, CookieService.REFRESH_COOKIE).orElse(null);
        authService.logout(refreshToken, httpResponse);
        return ResponseEntity.noContent().build();
    }

    @Operation(summary = "Log out all sessions", description = "Revokes all active refresh token sessions across all devices for the current user.")
    @SecurityRequirement(name = "cookieAuth")
    @SecurityRequirement(name = "csrfHeader")
    @ApiResponses({
            @ApiResponse(responseCode = "204", description = "All sessions revoked"),
            @ApiResponse(responseCode = "401", description = "Unauthenticated"),
            @ApiResponse(responseCode = "403", description = "Missing or invalid X-CSRF-Token header")
    })
    @PostMapping("/logout-all")
    public ResponseEntity<Void> logoutAll(Authentication authentication, HttpServletResponse httpResponse) {
        requireAuthenticated(authentication);
        authService.logoutAllSessions(authentication.getName(), httpResponse);
        return ResponseEntity.noContent().build();
    }

    @Operation(summary = "Get current user profile", description = "Returns the currently authenticated user's profile state and daily streak.")
    @SecurityRequirement(name = "cookieAuth")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Current user session details"),
            @ApiResponse(responseCode = "401", description = "Not authenticated / invalid session")
    })
    @GetMapping("/me")
    public ResponseEntity<UserResponse> me(Authentication authentication) {
        requireAuthenticated(authentication);
        AppUser user = userRepository.findById(authentication.getName())
                .orElseThrow(() -> new NotFoundException("User not found"));
        return ResponseEntity.ok(UserResponse.from(user));
    }

    @Operation(summary = "Request password reset code", description = "Generates a 6-digit password reset code and emails it if an account exists for the given address.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Reset request accepted"),
            @ApiResponse(responseCode = "429", description = "Rate limit exceeded (3 / hour / IP)")
    })
    @PostMapping("/forgot-password")
    public ResponseEntity<Map<String, String>> forgotPassword(@Valid @RequestBody com.incidentroom.dto.ForgotPasswordRequest request) {
        authService.forgotPassword(request);
        return ResponseEntity.ok(Map.of("message", "If an account with that email exists, a password reset code has been sent."));
    }

    @Operation(summary = "Reset password with code", description = "Verifies password reset code and sets a new password. Revokes all existing sessions.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Password reset successful"),
            @ApiResponse(responseCode = "401", description = "Invalid, expired, or rate-limited reset code"),
            @ApiResponse(responseCode = "429", description = "Rate limit exceeded (5 / minute / IP)")
    })
    @PostMapping("/reset-password")
    public ResponseEntity<Map<String, String>> resetPassword(@Valid @RequestBody com.incidentroom.dto.ResetPasswordRequest request) {
        authService.resetPassword(request);
        return ResponseEntity.ok(Map.of("message", "Password reset successfully. Please log in with your new password."));
    }


    private void requireAuthenticated(Authentication authentication) {
        if (authentication == null || !authentication.isAuthenticated()) {
            throw new InvalidCredentialsException();
        }
    }

    private Optional<String> readCookie(HttpServletRequest request, String name) {
        if (request.getCookies() == null) {
            return Optional.empty();
        }
        for (Cookie cookie : request.getCookies()) {
            if (cookie.getName().equals(name)) {
                return Optional.of(cookie.getValue());
            }
        }
        return Optional.empty();
    }
}
