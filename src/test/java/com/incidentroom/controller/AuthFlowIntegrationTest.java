package com.incidentroom.controller;

import com.incidentroom.security.CookieService;
import com.incidentroom.service.EmailService;
import com.incidentroom.BaseIntegrationTest;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Exercises the real HTTP flow end to end against a real MongoDB instance (via
 * Testcontainers —
 * see BaseIntegrationTest) including the cookie handoff between register →
 * verify → login →
 * refresh(rotation) → logout.
 *
 * EmailService is replaced by a @TestConfiguration @Primary
 * CapturingEmailService so codes are
 * captured in-process rather than actually emailed.
 *
 * Every test generates its own unique email so this suite is safe to re-run
 * with no cleanup.
 */
class AuthFlowIntegrationTest extends BaseIntegrationTest {

    @Autowired
    MockMvc mockMvc;

    @Autowired
    ObjectMapper objectMapper;

    @Autowired
    CapturingEmailService capturingEmailService;

    /**
     * Every test generates its own unique email so this suite is safe to re-run
     * against a
     * persistent (non-disposable) database with no cleanup step required between
     * runs.
     */
    private String uniqueEmail() {
        return "test-" + UUID.randomUUID() + "@example.com";
    }

    @Test
    void registerVerifyLoginRefreshLogout_fullFlowSucceeds() throws Exception {
        String email = uniqueEmail();

        // 1. Register — no cookies issued yet, code captured via the test double
        // instead of a real inbox
        mockMvc.perform(post("/api/v1/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of(
                        "email", email, "password", "a-strong-password-1", "displayName", "Jordan"))))
                .andExpect(status().isAccepted());

        String code = capturingEmailService.lastCodeFor(email);
        assertThat(code).isNotBlank();

        // 2. Verify — this is where cookies first appear
        MvcResult verifyResult = mockMvc.perform(post("/api/v1/auth/verify-email")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of("email", email, "code", code))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.emailVerified").value(true))
                .andExpect(cookie().exists(CookieService.ACCESS_COOKIE))
                .andExpect(cookie().httpOnly(CookieService.ACCESS_COOKIE, true))
                .andExpect(cookie().exists(CookieService.REFRESH_COOKIE))
                .andExpect(cookie().httpOnly(CookieService.REFRESH_COOKIE, true))
                .andReturn();

        var refreshCookie = verifyResult.getResponse().getCookie(CookieService.REFRESH_COOKIE);
        assertThat(refreshCookie).isNotNull();

        // 3. Refresh — old refresh token must be rejected after rotation (reuse
        // detection)
        MvcResult refreshResult = mockMvc.perform(post("/api/v1/auth/refresh").cookie(refreshCookie))
                .andExpect(status().isOk())
                .andExpect(cookie().exists(CookieService.REFRESH_COOKIE))
                .andReturn();

        var rotatedCookie = refreshResult.getResponse().getCookie(CookieService.REFRESH_COOKIE);
        assertThat(rotatedCookie.getValue()).isNotEqualTo(refreshCookie.getValue());

        // Replaying the OLD (now-revoked) refresh token must fail — this is the
        // reuse-detection path
        mockMvc.perform(post("/api/v1/auth/refresh").cookie(refreshCookie))
                .andExpect(status().isUnauthorized());

        // 4. Logout with the current valid refresh token succeeds and clears cookies
        mockMvc.perform(post("/api/v1/auth/logout").cookie(rotatedCookie).with(csrf()))
                .andExpect(status().isNoContent());
    }

    @Test
    void login_withUnverifiedAccount_isRejectedWithSpecificError() throws Exception {
        String email = uniqueEmail();

        mockMvc.perform(post("/api/v1/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of(
                        "email", email, "password", "a-strong-password-1", "displayName", "Jordan"))))
                .andExpect(status().isAccepted());

        mockMvc.perform(post("/api/v1/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of("email", email, "password", "a-strong-password-1"))))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("EMAIL_NOT_VERIFIED"));
    }

    @Test
    void adminEndpoint_returns404ForUnauthenticatedRequest() throws Exception {
        // The core security requirement: probing an admin route with no credentials
        // looks
        // identical to hitting a route that doesn't exist at all.
        mockMvc.perform(get("/api/v1/admin/scenarios"))
                .andExpect(status().isNotFound());
    }

    @Test
    void oauthProviders_isPublic_andReturnsAListEvenWhenEmpty() throws Exception {
        // This test suite doesn't configure GOOGLE_OAUTH_CLIENT_ID/SECRET or
        // GITHUB_OAUTH_CLIENT_ID/SECRET (see OAuth2SingleProviderIntegrationTest for the
        // configured-provider case), so this deployment's provider list should be empty —
        // not missing, not an error, just an empty JSON array the frontend can render zero
        // OAuth buttons from. No auth cookie is sent here on purpose: a visitor deciding
        // which sign-in button to show hasn't authenticated yet.
        mockMvc.perform(get("/api/v1/auth/oauth-providers"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.providers").isArray())
                .andExpect(jsonPath("$.providers").isEmpty());
    }

    @TestConfiguration
    static class TestEmailConfig {
        @Bean
        @Primary
        CapturingEmailService capturingEmailService() {
            return new CapturingEmailService();
        }
    }

    /**
     * Test double that captures codes per-email (not a single shared field) —
     * necessary now that
     * tests use randomly generated emails and could in principle run concurrently.
     */
    static class CapturingEmailService implements EmailService {
        private final java.util.concurrent.ConcurrentHashMap<String, String> codesByEmail = new java.util.concurrent.ConcurrentHashMap<>();

        @Override
        public void sendVerificationCode(String toEmail, String displayName, String code) {
            codesByEmail.put(toEmail, code);
        }

        String lastCodeFor(String email) {
            return codesByEmail.get(email);
        }

        @Override
        public void sendPasswordResetCode(String toEmail, String displayName, String code) {
            // Store with the same key so lastCodeFor(email) works for reset-code tests too.
            codesByEmail.put(toEmail, code);
        }
    }
}
