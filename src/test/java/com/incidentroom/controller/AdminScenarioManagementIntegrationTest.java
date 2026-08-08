package com.incidentroom.controller;

import com.incidentroom.BaseIntegrationTest;
import com.incidentroom.domain.AppUser;
import com.incidentroom.domain.Role;
import com.incidentroom.repository.AppUserRepository;
import com.incidentroom.security.JwtService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Covers the admin content-management surface added to close the gap where the only record of
 * "what scenarios exist" was a given admin's own browser localStorage (see the product analysis,
 * gap 3.1): GET list (drafts + published, filterable), GET detail-by-id (includes the answer key
 * for review), and DELETE (blocked while published).
 */
class AdminScenarioManagementIntegrationTest extends BaseIntegrationTest {

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;
    @Autowired AppUserRepository appUserRepository;
    @Autowired JwtService jwtService;

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
                title: "Admin management test scenario"
                difficulty: easy
                root_cause_class: bad_deploy
                symptom_summary: "Something is on fire."
                duration_target_seconds: 300

                seo:
                  meta_title: "Admin Mgmt Test"
                  meta_description: "A scenario used only to exercise the admin management endpoints."

                actions:
                  investigate:
                    - key: look_around
                      label: "Look around"
                      cost_seconds: 10
                  remediate:
                    - key: correct_fix
                      label: "The correct fix"
                      correctness: correct
                      score_points: 100
                      explanation: "This is the secret answer key text that must never leak publicly."

                telemetry: []
                postmortem: "n/a"
                """.formatted(slug);
    }

    private String importDraft(String slug, String adminToken) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/admin/scenarios/import")
                        .contentType(MediaType.TEXT_PLAIN)
                        .content(sampleYaml(slug))
                        .cookie(new jakarta.servlet.http.Cookie("ir_access", adminToken)))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString()).get("id").asText();
    }

    @Test
    void list_includesDraftsAndPublished_andIsInvisibleToNonAdmins() throws Exception {
        String adminToken = adminAccessToken();
        String slug = "admin-list-test-" + UUID.randomUUID().toString().substring(0, 8);
        String id = importDraft(slug, adminToken);

        // A freshly-imported draft shows up in the admin list immediately — this is the whole
        // point: no separate client-side bookkeeping is required to know it exists.
        mockMvc.perform(get("/api/v1/admin/scenarios")
                        .param("published", "false")
                        .cookie(new jakarta.servlet.http.Cookie("ir_access", adminToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[?(@.id=='" + id + "')]").exists())
                .andExpect(jsonPath("$.content[?(@.slug=='" + slug + "')].published").value(false));

        // Filtering to published=true must exclude it.
        mockMvc.perform(get("/api/v1/admin/scenarios")
                        .param("published", "true")
                        .cookie(new jakarta.servlet.http.Cookie("ir_access", adminToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[?(@.id=='" + id + "')]").doesNotExist());

        // Non-admins get the same masked 404 as every other admin route.
        String regularToken = regularUserAccessToken();
        mockMvc.perform(get("/api/v1/admin/scenarios")
                        .cookie(new jakarta.servlet.http.Cookie("ir_access", regularToken)))
                .andExpect(status().isNotFound());

        // Unauthenticated callers get the same 404, not a 401.
        mockMvc.perform(get("/api/v1/admin/scenarios"))
                .andExpect(status().isNotFound());
    }

    @Test
    void detail_includesAnswerKey_forAdminOnly() throws Exception {
        String adminToken = adminAccessToken();
        String slug = "admin-detail-test-" + UUID.randomUUID().toString().substring(0, 8);
        String id = importDraft(slug, adminToken);

        // Admin detail view legitimately includes the answer key — that's the point of this view.
        mockMvc.perform(get("/api/v1/admin/scenarios/" + id)
                        .cookie(new jakarta.servlet.http.Cookie("ir_access", adminToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.slug").value(slug))
                .andExpect(jsonPath("$.published").value(false))
                .andExpect(jsonPath("$.actions[?(@.key=='correct_fix')].outcome.correctness").exists())
                .andExpect(jsonPath("$.actions[?(@.key=='correct_fix')].outcome.explanation")
                        .value("This is the secret answer key text that must never leak publicly."));

        // The public detail endpoint for the same (still-draft) scenario must still 404.
        mockMvc.perform(get("/api/v1/scenarios/" + slug))
                .andExpect(status().isNotFound());
    }

    @Test
    void delete_succeedsForDraft_butIsBlockedWhilePublished() throws Exception {
        String adminToken = adminAccessToken();
        String slug = "admin-delete-test-" + UUID.randomUUID().toString().substring(0, 8);
        String id = importDraft(slug, adminToken);

        // Publish it.
        mockMvc.perform(patch("/api/v1/admin/scenarios/" + id + "/publish")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"publish\": true}")
                        .cookie(new jakarta.servlet.http.Cookie("ir_access", adminToken)))
                .andExpect(status().isOk());

        // Deleting a published scenario must be refused.
        mockMvc.perform(delete("/api/v1/admin/scenarios/" + id)
                        .cookie(new jakarta.servlet.http.Cookie("ir_access", adminToken)))
                .andExpect(status().isConflict());

        // Unpublish, then delete succeeds.
        mockMvc.perform(patch("/api/v1/admin/scenarios/" + id + "/publish")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"publish\": false}")
                        .cookie(new jakarta.servlet.http.Cookie("ir_access", adminToken)))
                .andExpect(status().isOk());

        mockMvc.perform(delete("/api/v1/admin/scenarios/" + id)
                        .cookie(new jakarta.servlet.http.Cookie("ir_access", adminToken)))
                .andExpect(status().isNoContent());

        // Now genuinely gone.
        mockMvc.perform(get("/api/v1/admin/scenarios/" + id)
                        .cookie(new jakarta.servlet.http.Cookie("ir_access", adminToken)))
                .andExpect(status().isNotFound());
    }

    @Test
    void delete_returns404ForNonAdmin() throws Exception {
        String adminToken = adminAccessToken();
        String slug = "admin-delete-nonadmin-test-" + UUID.randomUUID().toString().substring(0, 8);
        String id = importDraft(slug, adminToken);

        String regularToken = regularUserAccessToken();
        mockMvc.perform(delete("/api/v1/admin/scenarios/" + id)
                        .cookie(new jakarta.servlet.http.Cookie("ir_access", regularToken)))
                .andExpect(status().isNotFound());

        // Confirm it wasn't actually deleted.
        mockMvc.perform(get("/api/v1/admin/scenarios/" + id)
                        .cookie(new jakarta.servlet.http.Cookie("ir_access", adminToken)))
                .andExpect(status().isOk());
        assertThat(id).isNotBlank();
    }
}
