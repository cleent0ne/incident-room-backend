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
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class LeaderboardIntegrationTest extends BaseIntegrationTest {

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;
    @Autowired AppUserRepository appUserRepository;
    @Autowired JwtService jwtService;

    private String createAdminToken() {
        AppUser admin = AppUser.builder()
                .email("admin-" + UUID.randomUUID() + "@example.com").displayName("Admin")
                .emailVerified(true).role(Role.ADMIN).createdAt(Instant.now()).updatedAt(Instant.now()).build();
        appUserRepository.save(admin);
        return jwtService.issueAccessToken(admin);
    }

    private String createUserToken(String displayName) {
        AppUser user = AppUser.builder()
                .email("user-" + UUID.randomUUID() + "@example.com").displayName(displayName)
                .emailVerified(true).role(Role.USER).createdAt(Instant.now()).updatedAt(Instant.now()).build();
        appUserRepository.save(user);
        return jwtService.issueAccessToken(user);
    }

    private String sampleYaml(String slug) {
        return """
                slug: %s
                title: "Leaderboard test scenario"
                difficulty: easy
                root_cause_class: bad_deploy
                symptom_summary: "Test symptom"
                duration_target_seconds: 100

                seo:
                  meta_title: "Title"
                  meta_description: "Desc"

                topology:
                  nodes:
                    - { id: lb, type: load_balancer, label: "Load Balancer" }
                  edges: []

                actions:
                  investigate:
                    - key: check_logs
                      label: "Check logs"
                      cost_seconds: 10
                  remediate:
                    - key: rollback
                      label: "Rollback"
                      correctness: correct
                      score_points: 100
                      explanation: "Fixed"

                postmortem: |
                  ## Postmortem
                  Fixed cleanly.
                """.formatted(slug);
    }

    @Test
    void leaderboard_recordsCompletions_andRanksByScore() throws Exception {
        String slug = "leaderboard-scenario-" + UUID.randomUUID();
        String adminToken = createAdminToken();

        // 1. Import and publish scenario
        MvcResult importResult = mockMvc.perform(post("/api/v1/admin/scenarios/import")
                        .cookie(new jakarta.servlet.http.Cookie("ir_access", adminToken))
                        .contentType(MediaType.TEXT_PLAIN)
                        .content(sampleYaml(slug)))
                .andExpect(status().isOk())
                .andReturn();

        String scenarioId = objectMapper.readTree(importResult.getResponse().getContentAsString()).get("id").asText();

        mockMvc.perform(patch("/api/v1/admin/scenarios/" + scenarioId + "/publish")
                        .cookie(new jakarta.servlet.http.Cookie("ir_access", adminToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"publish\": true}"))
                .andExpect(status().isOk());

        // 2. User 1 completes the scenario
        String token1 = createUserToken("Alice");
        MvcResult attempt1Result = mockMvc.perform(post("/api/v1/attempts")
                        .cookie(new jakarta.servlet.http.Cookie("ir_access", token1))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("scenarioSlug", slug)))
                        .with(csrf()))
                .andExpect(status().isCreated())
                .andReturn();

        String attempt1Id = objectMapper.readTree(attempt1Result.getResponse().getContentAsString()).get("attemptId").asText();

        mockMvc.perform(post("/api/v1/attempts/" + attempt1Id + "/resolve")
                        .cookie(new jakarta.servlet.http.Cookie("ir_access", token1))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("actionKey", "rollback")))
                        .with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.percentile").value(100));

        // 3. Fetch leaderboard for scenario
        mockMvc.perform(get("/api/v1/scenarios/" + slug + "/leaderboard"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.scenarioSlug").value(slug))
                .andExpect(jsonPath("$.totalEntries").value(1))
                .andExpect(jsonPath("$.entries[0].displayName").value("Alice"))
                .andExpect(jsonPath("$.entries[0].rank").value(1));
    }
}
