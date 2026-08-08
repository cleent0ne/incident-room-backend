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

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Every mutating call (POST to /api/v1/attempts/**) attaches .with(csrf()) — these endpoints are
 * permitAll for AUTHORIZATION (anonymous play is first-class) but deliberately remain
 * CSRF-protected, since an authenticated user playing while logged in still has a session a
 * forged cross-site request could ride on top of. This mirrors what a real frontend does: it
 * always has the CSRF cookie by the time it POSTs, from an earlier page-load GET.
 *
 * Extends BaseIntegrationTest which starts a shared Testcontainers MongoDBContainer. No
 * localhost:27017 dependency — CI only requires Docker.
 */
class AttemptFlowIntegrationTest extends BaseIntegrationTest {

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

    /** duration_target_seconds=100, one investigate action costing 20s — chosen so the time-bonus
     *  math is easy to hand-verify: elapsed=20, target=100 -> remainingFraction=0.8 -> bonus=round(0.8*50)=40. */
    private String sampleYaml(String slug) {
        return """
                slug: %s
                title: "Attempt flow fixture"
                difficulty: easy
                root_cause_class: bad_deploy
                symptom_summary: "Something is on fire."
                duration_target_seconds: 100

                seo:
                  meta_title: "Fixture scenario"
                  meta_description: "Used by AttemptFlowIntegrationTest."

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
                    - key: restart_service
                      label: "Restart the service"
                      correctness: harmful
                      score_points: -20
                      explanation: "Makes it worse."

                telemetry:
                  - { t: 0, type: metric_point, source: svc, payload: { name: error_rate, value: 0.5 } }
                  - { t: 2, type: log, source: deploy-bot, revealed_by: check_deploys, payload: { level: INFO, message: "deploy at 13:58" } }

                postmortem: |
                  ## What happened
                  A bad deploy caused errors.
                """.formatted(slug);
    }

    /** Imports + publishes a fresh fixture scenario and returns its slug. */
    private String publishedScenarioSlug() throws Exception {
        String slug = "attempt-fixture-" + UUID.randomUUID().toString().substring(0, 8);
        String adminToken = adminAccessToken();

        MvcResult importResult = mockMvc.perform(post("/api/v1/admin/scenarios/import")
                        .contentType(MediaType.TEXT_PLAIN).content(sampleYaml(slug))
                        .cookie(new Cookie("ir_access", adminToken)))
                .andExpect(status().isOk()).andReturn();
        String id = objectMapper.readTree(importResult.getResponse().getContentAsString()).get("id").asText();

        mockMvc.perform(patch("/api/v1/admin/scenarios/" + id + "/publish")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"publish\": true}")
                        .cookie(new Cookie("ir_access", adminToken)))
                .andExpect(status().isOk());

        return slug;
    }

    @Test
    void anonymousFullFlow_startInvestigateResolve_scoresCorrectly() throws Exception {
        String slug = publishedScenarioSlug();

        // Start — no auth cookie at all
        MvcResult startResult = mockMvc.perform(post("/api/v1/attempts")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"scenarioSlug\": \"" + slug + "\"}")
                        .with(csrf()))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.scenarioSlug").value(slug))
                .andExpect(jsonPath("$.status").value("IN_PROGRESS"))
                .andExpect(jsonPath("$.actions.length()").value(3)) // 1 investigate + 2 remediate, labels only
                .andExpect(jsonPath("$.revealedTelemetry.length()").value(1)) // only the ambient event so far
                .andExpect(jsonPath("$.capabilityToken").isNotEmpty())
                .andReturn();
        com.fasterxml.jackson.databind.JsonNode startJson = objectMapper.readTree(startResult.getResponse().getContentAsString());
        String attemptId = startJson.get("attemptId").asText();
        String capToken = startJson.get("capabilityToken").asText();

        // Investigate — reveals the gated telemetry event (with X-Capability-Token header)
        mockMvc.perform(post("/api/v1/attempts/" + attemptId + "/actions")
                        .header("X-Capability-Token", capToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"actionKey\": \"check_deploys\"}")
                        .with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.revealedTelemetry.length()").value(1))
                .andExpect(jsonPath("$.revealedTelemetry[0].payload.message").value("deploy at 13:58"))
                .andExpect(jsonPath("$.costSeconds").value(20))
                .andExpect(jsonPath("$.elapsedSeconds").value(20));

        // Repeating the same action is idempotent — no double time charge
        mockMvc.perform(post("/api/v1/attempts/" + attemptId + "/actions")
                        .header("X-Capability-Token", capToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"actionKey\": \"check_deploys\"}")
                        .with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.elapsedSeconds").value(20));

        // Resolve with the correct remediation: score=100, elapsed=20/target=100 -> bonus=round(0.8*50)=40
        mockMvc.perform(post("/api/v1/attempts/" + attemptId + "/resolve")
                        .header("X-Capability-Token", capToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"actionKey\": \"rollback\"}")
                        .with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.correctness").value("CORRECT"))
                .andExpect(jsonPath("$.scorePoints").value(100))
                .andExpect(jsonPath("$.timeBonus").value(40))
                .andExpect(jsonPath("$.finalScore").value(140))
                .andExpect(jsonPath("$.elapsedSeconds").value(20));

        // Idempotent resolve — calling it again returns the SAME result, doesn't re-score
        mockMvc.perform(post("/api/v1/attempts/" + attemptId + "/resolve")
                        .header("X-Capability-Token", capToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"actionKey\": \"restart_service\"}") // even with a different action key
                        .with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.finalScore").value(140)) // unchanged — the original correct answer stands
                .andExpect(jsonPath("$.correctness").value("CORRECT"));
    }

    @Test
    void harmfulRemediation_producesScoreFlooredAtZero() throws Exception {
        String slug = publishedScenarioSlug();

        MvcResult startResult = mockMvc.perform(post("/api/v1/attempts")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"scenarioSlug\": \"" + slug + "\"}")
                        .with(csrf()))
                .andExpect(status().isCreated()).andReturn();
        com.fasterxml.jackson.databind.JsonNode startJson = objectMapper.readTree(startResult.getResponse().getContentAsString());
        String attemptId = startJson.get("attemptId").asText();
        String capToken = startJson.get("capabilityToken").asText();

        // Resolve immediately (elapsed=0) with the harmful action — asserts the floor logic holds:
        // finalScore should never go below 0 regardless of how negative scorePoints is.
        mockMvc.perform(post("/api/v1/attempts/" + attemptId + "/resolve")
                        .header("X-Capability-Token", capToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"actionKey\": \"restart_service\"}")
                        .with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.correctness").value("HARMFUL"))
                .andExpect(jsonPath("$.finalScore", org.hamcrest.Matchers.greaterThanOrEqualTo(0)));
    }

    @Test
    void ownedAttempt_isNotAccessibleByADifferentAuthenticatedUser() throws Exception {
        String slug = publishedScenarioSlug();
        String ownerToken = regularUserAccessToken();
        String otherToken = regularUserAccessToken();

        MvcResult startResult = mockMvc.perform(post("/api/v1/attempts")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"scenarioSlug\": \"" + slug + "\"}")
                        .cookie(new Cookie("ir_access", ownerToken))
                        .with(csrf()))
                .andExpect(status().isCreated()).andReturn();
        String attemptId = objectMapper.readTree(startResult.getResponse().getContentAsString()).get("attemptId").asText();

        // Owner can read it back
        mockMvc.perform(get("/api/v1/attempts/" + attemptId).cookie(new Cookie("ir_access", ownerToken)))
                .andExpect(status().isOk());

        // A different authenticated user gets 404, not 403 — existence isn't confirmed either way
        mockMvc.perform(get("/api/v1/attempts/" + attemptId).cookie(new Cookie("ir_access", otherToken)))
                .andExpect(status().isNotFound());

        // An anonymous caller (no cookie at all) also gets 404 — the attempt is owned, not a shared capability
        mockMvc.perform(get("/api/v1/attempts/" + attemptId))
                .andExpect(status().isNotFound());
    }

    @Test
    void anonymousAttempt_isAccessibleByAnyoneHoldingTheCapabilityToken() throws Exception {
        String slug = publishedScenarioSlug();

        AnonymousAttempt anon = startAnonymousAttempt(slug);

        // Capability token header grants access to state
        mockMvc.perform(get("/api/v1/attempts/" + anon.attemptId).header("X-Capability-Token", anon.capabilityToken))
                .andExpect(status().isOk());

        // Without header -> 404
        mockMvc.perform(get("/api/v1/attempts/" + anon.attemptId))
                .andExpect(status().isNotFound());
    }

    @Test
    void unknownActionKey_isRejected() throws Exception {
        String slug = publishedScenarioSlug();

        AnonymousAttempt anon = startAnonymousAttempt(slug);

        mockMvc.perform(post("/api/v1/attempts/" + anon.attemptId + "/actions")
                        .header("X-Capability-Token", anon.capabilityToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"actionKey\": \"does_not_exist\"}")
                        .with(csrf()))
                .andExpect(status().isBadRequest());

        mockMvc.perform(post("/api/v1/attempts/" + anon.attemptId + "/resolve")
                        .header("X-Capability-Token", anon.capabilityToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"actionKey\": \"does_not_exist\"}")
                        .with(csrf()))
                .andExpect(status().isBadRequest());
    }

    @Test
    void startingAgainstAnUnpublishedOrNonexistentSlug_returns404() throws Exception {
        mockMvc.perform(post("/api/v1/attempts")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"scenarioSlug\": \"definitely-does-not-exist\"}")
                        .with(csrf()))
                .andExpect(status().isNotFound());
    }

    @Test
    void claim_attachesAnonymousAttemptsToTheAuthenticatedCaller() throws Exception {
        String slug = publishedScenarioSlug();
        String userToken = regularUserAccessToken();

        // Two anonymous attempts (no cookie) — exactly the "played before signing up" case.
        AnonymousAttempt anon1 = startAnonymousAttempt(slug);
        AnonymousAttempt anon2 = startAnonymousAttempt(slug);

        mockMvc.perform(post("/api/v1/attempts/claim")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"capabilityTokens\": [\"" + anon1.capabilityToken + "\", \"" + anon2.capabilityToken + "\"]}")
                        .cookie(new Cookie("ir_access", userToken))
                        .with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.claimedCount").value(2))
                .andExpect(jsonPath("$.skippedCount").value(0));

        // Now owned — no longer accessible anonymously, but readable by the claiming user.
        mockMvc.perform(get("/api/v1/attempts/" + anon1.attemptId))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/api/v1/attempts/" + anon1.attemptId).cookie(new Cookie("ir_access", userToken)))
                .andExpect(status().isOk());

        // Re-claiming the same tokens is idempotent — still counted as claimed, not an error.
        mockMvc.perform(post("/api/v1/attempts/claim")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"capabilityTokens\": [\"" + anon1.capabilityToken + "\"]}")
                        .cookie(new Cookie("ir_access", userToken))
                        .with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.claimedCount").value(1))
                .andExpect(jsonPath("$.skippedCount").value(0));
    }

    @Test
    void claim_skipsUnknownAndAlreadyOwnedByAnotherUserAttempts() throws Exception {
        String slug = publishedScenarioSlug();
        String ownerToken = regularUserAccessToken();
        String claimerToken = regularUserAccessToken();

        // Already owned by a different user.
        MvcResult ownedResult = mockMvc.perform(post("/api/v1/attempts")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"scenarioSlug\": \"" + slug + "\"}")
                        .cookie(new Cookie("ir_access", ownerToken))
                        .with(csrf()))
                .andExpect(status().isCreated()).andReturn();
        String ownedAttemptId = objectMapper.readTree(ownedResult.getResponse().getContentAsString()).get("attemptId").asText();

        mockMvc.perform(post("/api/v1/attempts/claim")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"capabilityTokens\": [\"does-not-exist\"]}")
                        .cookie(new Cookie("ir_access", claimerToken))
                        .with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.claimedCount").value(0))
                .andExpect(jsonPath("$.skippedCount").value(1));

        // Ownership was never overwritten — still the original owner's.
        mockMvc.perform(get("/api/v1/attempts/" + ownedAttemptId).cookie(new Cookie("ir_access", claimerToken)))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/api/v1/attempts/" + ownedAttemptId).cookie(new Cookie("ir_access", ownerToken)))
                .andExpect(status().isOk());
    }

    @Test
    void claim_requiresAuthentication() throws Exception {
        String slug = publishedScenarioSlug();
        AnonymousAttempt anon = startAnonymousAttempt(slug);

        // No ir_access cookie at all — an anonymous caller can't claim, only an authenticated one can.
        mockMvc.perform(post("/api/v1/attempts/claim")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"capabilityTokens\": [\"" + anon.capabilityToken + "\"]}")
                        .with(csrf()))
                .andExpect(status().isUnauthorized());
    }

    private record AnonymousAttempt(String attemptId, String capabilityToken) {}

    private AnonymousAttempt startAnonymousAttempt(String slug) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/attempts")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"scenarioSlug\": \"" + slug + "\"}")
                        .with(csrf()))
                .andExpect(status().isCreated()).andReturn();
        com.fasterxml.jackson.databind.JsonNode node = objectMapper.readTree(result.getResponse().getContentAsString());
        return new AnonymousAttempt(node.get("attemptId").asText(), node.get("capabilityToken").asText());
    }
}