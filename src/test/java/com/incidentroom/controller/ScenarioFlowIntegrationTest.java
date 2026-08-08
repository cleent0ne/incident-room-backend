package com.incidentroom.controller;

import com.incidentroom.domain.AppUser;
import com.incidentroom.domain.Role;
import com.incidentroom.repository.AppUserRepository;
import com.incidentroom.security.JwtService;
import com.incidentroom.BaseIntegrationTest;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Exercises the public scenario read API end-to-end against a real MongoDB instance via
 * Testcontainers (see BaseIntegrationTest). Every test uses a randomly generated slug so this
 * suite is safe to re-run with no cleanup required.
 */
class ScenarioFlowIntegrationTest extends BaseIntegrationTest {

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;
    @Autowired AppUserRepository appUserRepository;
    @Autowired JwtService jwtService;

    /** Bypasses the real registration flow entirely — this test is about the scenario pipeline,
     *  not auth, so an admin user is created directly and a valid access token minted for it. */
    private String adminAccessToken() {
        AppUser admin = AppUser.builder()
                .email("admin-" + UUID.randomUUID() + "@example.com")
                .displayName("Admin")
                .emailVerified(true)
                .role(Role.ADMIN)
                .createdAt(Instant.now())
                .updatedAt(Instant.now())
                .build();
        appUserRepository.save(admin);
        return jwtService.issueAccessToken(admin);
    }

    private String regularUserAccessToken() {
        AppUser user = AppUser.builder()
                .email("user-" + UUID.randomUUID() + "@example.com")
                .displayName("Regular User")
                .emailVerified(true)
                .role(Role.USER)
                .createdAt(Instant.now())
                .updatedAt(Instant.now())
                .build();
        appUserRepository.save(user);
        return jwtService.issueAccessToken(user);
    }

    private String sampleYaml(String slug) {
        return """
                slug: %s
                title: "Checkout latency spike"
                difficulty: medium
                root_cause_class: cache_stampede
                symptom_summary: "Checkout p99 latency jumped from 120ms to 8.2s at 14:02 UTC."
                duration_target_seconds: 480

                seo:
                  meta_title: "Cache Stampede Incident"
                  meta_description: "Practice diagnosing a live cache-stampede production incident."

                topology:
                  nodes:
                    - { id: lb, type: load_balancer, label: "Load Balancer" }
                    - { id: checkout, type: service, label: "Checkout Service" }
                  edges:
                    - { from: lb, to: checkout }

                actions:
                  investigate:
                    - key: check_cache_hit_rate
                      label: "Check cache hit-rate metric"
                      cost_seconds: 20
                  remediate:
                    - key: warm_cache_add_lock
                      label: "Add cache-stampede lock"
                      correctness: correct
                      score_points: 100
                      explanation: "Correct because it prevents the stampede."
                    - key: scale_up_db
                      label: "Scale up the database"
                      correctness: partially_correct
                      score_points: 50
                      explanation: "Masks the symptom without fixing the cause."

                telemetry:
                  - { t: 0, type: metric_point, source: checkout, payload: { name: p99_latency_ms, value: 8200 } }
                  - { t: 5, type: log, source: cache, revealed_by: check_cache_hit_rate, payload: { level: WARN, message: "cache hit rate dropped" } }

                postmortem: |
                  ## What happened
                  The cache key expired under high concurrent load.
                """.formatted(slug);
    }

    @Test
    void importThenPublish_makesScenarioPubliclyReadable_withoutLeakingAnswerKey() throws Exception {
        String slug = "test-scenario-" + UUID.randomUUID().toString().substring(0, 8);
        String adminToken = adminAccessToken();

        // 1. Draft scenarios are not publicly visible yet — 404 exactly like a nonexistent slug
        mockMvc.perform(get("/api/v1/scenarios/" + slug))
                .andExpect(status().isNotFound());

        // 2. Import as admin
        MvcResult importResult = mockMvc.perform(post("/api/v1/admin/scenarios/import")
                        .contentType(MediaType.TEXT_PLAIN)
                        .content(sampleYaml(slug))
                        .cookie(new jakarta.servlet.http.Cookie("ir_access", adminToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.slug").value(slug))
                .andExpect(jsonPath("$.published").value(false))
                .andExpect(jsonPath("$.validationWarnings").isEmpty()) // sample YAML is fully valid
                .andReturn();

        String scenarioId = objectMapper.readTree(importResult.getResponse().getContentAsString()).get("id").asText();

        // 3. Still not publicly visible before publish
        mockMvc.perform(get("/api/v1/scenarios/" + slug))
                .andExpect(status().isNotFound());

        // 4. Publish
        mockMvc.perform(patch("/api/v1/admin/scenarios/" + scenarioId + "/publish")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"publish\": true}")
                        .cookie(new jakarta.servlet.http.Cookie("ir_access", adminToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.published").value(true));

        // 5. Now publicly readable, and the answer key must NOT be present anywhere in the response
        MvcResult detailResult = mockMvc.perform(get("/api/v1/scenarios/" + slug))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.title").value("Checkout latency spike"))
                .andExpect(jsonPath("$.actions.length()").value(3)) // 1 investigate + 2 remediate labels, no outcome data
                .andReturn();

        String body = detailResult.getResponse().getContentAsString();
        assertThat(body).doesNotContain("correctness");
        assertThat(body).doesNotContain("scorePoints");
        assertThat(body).doesNotContain("Correct because it prevents the stampede"); // the explanation text itself

        // 6. Gated telemetry (revealedBy != null) must not appear on the public detail page either —
        //    only the ambient metric_point event should be present.
        assertThat(body).doesNotContain("cache hit rate dropped");
        assertThat(body).contains("p99_latency_ms");

        // 7. Appears in the catalog and sitemap
        mockMvc.perform(get("/api/v1/scenarios").param("difficulty", "MEDIUM").param("size", "100"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[?(@.slug=='" + slug + "')]").exists());

        mockMvc.perform(get("/api/v1/sitemap-data"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.slug=='" + slug + "')]").exists());
    }

    @Test
    void reimportingSameSlug_updatesInPlace_andDoesNotResetPublishState() throws Exception {
        String slug = "test-reimport-" + UUID.randomUUID().toString().substring(0, 8);
        String adminToken = adminAccessToken();

        MvcResult first = mockMvc.perform(post("/api/v1/admin/scenarios/import")
                        .contentType(MediaType.TEXT_PLAIN)
                        .content(sampleYaml(slug))
                        .cookie(new jakarta.servlet.http.Cookie("ir_access", adminToken)))
                .andExpect(status().isOk())
                .andReturn();
        String id = objectMapper.readTree(first.getResponse().getContentAsString()).get("id").asText();

        mockMvc.perform(patch("/api/v1/admin/scenarios/" + id + "/publish")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"publish\": true}")
                        .cookie(new jakarta.servlet.http.Cookie("ir_access", adminToken)))
                .andExpect(status().isOk());

        // Re-import the same slug (e.g. fixing a typo in the postmortem)
        mockMvc.perform(post("/api/v1/admin/scenarios/import")
                        .contentType(MediaType.TEXT_PLAIN)
                        .content(sampleYaml(slug))
                        .cookie(new jakarta.servlet.http.Cookie("ir_access", adminToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(id))          // same document, not a duplicate
                .andExpect(jsonPath("$.published").value(true)); // publish state untouched by re-import
    }

    @Test
    void adminImport_returns404ForNonAdminUser() throws Exception {
        String regularToken = regularUserAccessToken();

        mockMvc.perform(post("/api/v1/admin/scenarios/import")
                        .contentType(MediaType.TEXT_PLAIN)
                        .content(sampleYaml("irrelevant-slug"))
                        .cookie(new jakarta.servlet.http.Cookie("ir_access", regularToken)))
                .andExpect(status().isNotFound());
    }

    @Test
    void publishing_failsValidation_forScenarioWithNoCorrectRemediation() throws Exception {
        String slug = "test-invalid-" + UUID.randomUUID().toString().substring(0, 8);
        String adminToken = adminAccessToken();

        String brokenYaml = """
                slug: %s
                title: "Broken scenario"
                difficulty: easy
                root_cause_class: bad_deploy
                symptom_summary: "Something broke."
                duration_target_seconds: 300

                seo:
                  meta_title: "Broken"
                  meta_description: "Broken description."

                actions:
                  investigate:
                    - key: look_around
                      label: "Look around"
                      cost_seconds: 10
                  remediate:
                    - key: wrong_fix
                      label: "Wrong fix"
                      correctness: incorrect
                      score_points: 10
                      explanation: "This is wrong on purpose for the test."

                telemetry: []
                postmortem: "n/a"
                """.formatted(slug);

        MvcResult importResult = mockMvc.perform(post("/api/v1/admin/scenarios/import")
                        .contentType(MediaType.TEXT_PLAIN)
                        .content(brokenYaml)
                        .cookie(new jakarta.servlet.http.Cookie("ir_access", adminToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.validationWarnings").isNotEmpty()) // import succeeds, but flags the problem
                .andReturn();

        String id = objectMapper.readTree(importResult.getResponse().getContentAsString()).get("id").asText();

        // Publishing must be hard-blocked
        mockMvc.perform(patch("/api/v1/admin/scenarios/" + id + "/publish")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"publish\": true}")
                        .cookie(new jakarta.servlet.http.Cookie("ir_access", adminToken)))
                .andExpect(status().isBadRequest());
    }
}
