package com.incidentroom.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.incidentroom.BaseIntegrationTest;
import com.incidentroom.domain.AppUser;
import com.incidentroom.domain.Role;
import com.incidentroom.repository.AppUserRepository;
import com.incidentroom.security.JwtService;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.time.Instant;
import java.util.UUID;

import static org.hamcrest.Matchers.nullValue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class UserFlowIntegrationTest extends BaseIntegrationTest {

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;
    @Autowired AppUserRepository appUserRepository;
    @Autowired JwtService jwtService;

    private String adminAccessToken() {
        AppUser admin = AppUser.builder()
                .email("admin-" + UUID.randomUUID() + "@example.com").displayName("Admin")
                .emailVerified(true).role(Role.ADMIN).createdAt(Instant.now()).updatedAt(Instant.now()).build();
        appUserRepository.save(admin);
        return jwtService.issueAccessToken(admin);
    }

    private String regularUserAccessToken() {
        AppUser user = AppUser.builder()
                .email("user-" + UUID.randomUUID() + "@example.com").displayName("Player")
                .emailVerified(true).role(Role.USER).createdAt(Instant.now()).updatedAt(Instant.now()).build();
        appUserRepository.save(user);
        return jwtService.issueAccessToken(user);
    }

    private String sampleYaml(String slug, String title) {
        return """
                slug: %s
                title: "%s"
                difficulty: easy
                root_cause_class: bad_deploy
                symptom_summary: "Something is on fire."
                duration_target_seconds: 100

                seo:
                  meta_title: "Fixture scenario"
                  meta_description: "Used by UserFlowIntegrationTest."

                actions:
                  investigate:
                    - key: check_deploys
                      label: "Check recent deploys"
                      cost_seconds: 20
                  remediate:
                    - key: rollback
                      label: "Roll back the deploy"
                      correctness: correct
                      score_points: 100
                      explanation: "Correct — the deploy caused it."

                postmortem: |
                  ## What happened
                  A bad deploy caused errors.
                """.formatted(slug, title);
    }

    /** Imports + publishes a fresh fixture scenario and returns its slug. */
    private String publishedScenarioSlug(String title) throws Exception {
        String slug = "history-fixture-" + UUID.randomUUID().toString().substring(0, 8);
        String adminToken = adminAccessToken();

        MvcResult importResult = mockMvc.perform(post("/api/v1/admin/scenarios/import")
                        .contentType(MediaType.TEXT_PLAIN).content(sampleYaml(slug, title))
                        .cookie(new Cookie("ir_access", adminToken)))
                .andExpect(status().isOk()).andReturn();
        String id = objectMapper.readTree(importResult.getResponse().getContentAsString()).get("id").asText();

        mockMvc.perform(patch("/api/v1/admin/scenarios/" + id + "/publish")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"publish\": true}")
                        .cookie(new Cookie("ir_access", adminToken)))
                .andExpect(status().isOk());

        return slug;
    }

    private String startAttempt(String slug, String userToken) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/attempts")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"scenarioSlug\": \"" + slug + "\"}")
                        .cookie(new Cookie("ir_access", userToken))
                        .with(csrf()))
                .andExpect(status().isCreated()).andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString()).get("attemptId").asText();
    }

    @Test
    void myAttempts_returnsOnlyTheCallersAttempts_mostRecentFirst_withScenarioTitleResolved() throws Exception {
        String userToken = regularUserAccessToken();
        String otherUserToken = regularUserAccessToken();

        String slugA = publishedScenarioSlug("First fixture scenario");
        String slugB = publishedScenarioSlug("Second fixture scenario");

        String attemptA = startAttempt(slugA, userToken);
        String attemptB = startAttempt(slugB, userToken);
        startAttempt(slugA, otherUserToken); // belongs to someone else — must not show up below

        mockMvc.perform(get("/api/v1/users/me/attempts").cookie(new Cookie("ir_access", userToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(2))
                // most recent first
                .andExpect(jsonPath("$.content[0].attemptId").value(attemptB))
                .andExpect(jsonPath("$.content[0].scenarioSlug").value(slugB))
                .andExpect(jsonPath("$.content[0].scenarioTitle").value("Second fixture scenario"))
                .andExpect(jsonPath("$.content[0].status").value("IN_PROGRESS"))
                .andExpect(jsonPath("$.content[1].attemptId").value(attemptA))
                .andExpect(jsonPath("$.content[1].scenarioTitle").value("First fixture scenario"));
    }

    @Test
    void myAttempts_reflectsCompletionAndScoreAfterResolve() throws Exception {
        String userToken = regularUserAccessToken();
        String slug = publishedScenarioSlug("Resolvable fixture scenario");
        String attemptId = startAttempt(slug, userToken);

        mockMvc.perform(post("/api/v1/attempts/" + attemptId + "/resolve")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"actionKey\": \"rollback\"}")
                        .cookie(new Cookie("ir_access", userToken))
                        .with(csrf()))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/v1/users/me/attempts").cookie(new Cookie("ir_access", userToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].status").value("COMPLETED"))
                .andExpect(jsonPath("$.content[0].finalScore").value(150)) // 100 base + 50 max time bonus (resolved immediately)
                .andExpect(jsonPath("$.content[0].completedAt").exists());
    }

    @Test
    void myAttempts_requiresAuthentication() throws Exception {
        mockMvc.perform(get("/api/v1/users/me/attempts"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void myAttempts_respectsPageSizeCap() throws Exception {
        String userToken = regularUserAccessToken();

        mockMvc.perform(get("/api/v1/users/me/attempts?size=500").cookie(new Cookie("ir_access", userToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.page.size").value(50)); // caller asked for 500, capped at 50
    }

    @Test
    void myStats_reflectsFullHistory_notJustOnePage() throws Exception {
        // The point of this endpoint existing separately from myAttempts: it must be correct
        // for a user with more completed attempts than fit on one page, computed server-side
        // via aggregation rather than the frontend approximating from whatever page it has
        // loaded.
        String userToken = regularUserAccessToken();
        String slugA = publishedScenarioSlug("Stats fixture A");
        String slugB = publishedScenarioSlug("Stats fixture B");

        String attemptA = startAttempt(slugA, userToken);
        mockMvc.perform(post("/api/v1/attempts/" + attemptA + "/resolve")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"actionKey\": \"rollback\"}")
                        .cookie(new Cookie("ir_access", userToken)).with(csrf()))
                .andExpect(status().isOk()); // 150 pts (100 base + 50 max time bonus)

        String attemptB = startAttempt(slugB, userToken);
        mockMvc.perform(post("/api/v1/attempts/" + attemptB + "/resolve")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"actionKey\": \"rollback\"}")
                        .cookie(new Cookie("ir_access", userToken)).with(csrf()))
                .andExpect(status().isOk()); // also 150 pts

        mockMvc.perform(get("/api/v1/users/me/stats").cookie(new Cookie("ir_access", userToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.completedCount").value(2))
                .andExpect(jsonPath("$.averageScore").value(150.0));
    }

    @Test
    void myStats_returnsZeroCountAndNullAverage_notAnErrorOrZeroAverage_whenNoCompletedAttempts() throws Exception {
        String userToken = regularUserAccessToken();

        mockMvc.perform(get("/api/v1/users/me/stats").cookie(new Cookie("ir_access", userToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.completedCount").value(0))
                .andExpect(jsonPath("$.averageScore").value(nullValue())); // null, not 0 — see UserStatsDto javadoc
    }

    @Test
    void myStats_excludesInProgressAndOtherUsersAttempts() throws Exception {
        String userToken = regularUserAccessToken();
        String otherUserToken = regularUserAccessToken();
        String slug = publishedScenarioSlug("Stats exclusion fixture");

        startAttempt(slug, userToken); // left in progress — must not count
        String otherAttempt = startAttempt(slug, otherUserToken);
        mockMvc.perform(post("/api/v1/attempts/" + otherAttempt + "/resolve")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"actionKey\": \"rollback\"}")
                        .cookie(new Cookie("ir_access", otherUserToken)).with(csrf()))
                .andExpect(status().isOk()); // belongs to someone else — must not count either

        mockMvc.perform(get("/api/v1/users/me/stats").cookie(new Cookie("ir_access", userToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.completedCount").value(0));
    }

    @Test
    void myStats_requiresAuthentication() throws Exception {
        mockMvc.perform(get("/api/v1/users/me/stats"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void authMe_exposesOauthProvider_nullForPasswordAccounts() throws Exception {
        String userToken = regularUserAccessToken();

        mockMvc.perform(get("/api/v1/auth/me").cookie(new Cookie("ir_access", userToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.oauthProvider").value(nullValue()));
    }
}