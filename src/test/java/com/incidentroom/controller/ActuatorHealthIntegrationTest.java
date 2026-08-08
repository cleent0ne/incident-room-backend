package com.incidentroom.controller;

import com.incidentroom.BaseIntegrationTest;
import com.incidentroom.domain.AppUser;
import com.incidentroom.domain.Role;
import com.incidentroom.repository.AppUserRepository;
import com.incidentroom.security.JwtService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Covers the actuator health endpoint end-to-end through real Spring Security, proving
 * two things the config alone doesn't guarantee just by existing:
 *   1. It's genuinely public (an uptime monitor needs no credentials to check it).
 *   2. Component-level detail (which Mongo host/version got probed, cache stats, etc.)
 *      is visible to ADMIN callers only — not to any authenticated user, which was the
 *      gap before {@code management.endpoint.health.roles: ADMIN} was added (the
 *      previous default, "when-authorized" with no roles configured, treats any
 *      authenticated principal as authorized).
 *
 * The genuine-failure-detection case (does this actually go DOWN when Mongo is
 * unreachable) is covered separately in {@code MongoHealthIndicatorTest}, which isn't
 * confounded by full Spring context startup behavior — see that class's javadoc.
 */
class ActuatorHealthIntegrationTest extends BaseIntegrationTest {

    @Autowired MockMvc mockMvc;
    @Autowired AppUserRepository appUserRepository;
    @Autowired JwtService jwtService;

    private String tokenFor(Role role) {
        AppUser user = AppUser.builder()
                .email(role.name().toLowerCase() + "-" + UUID.randomUUID() + "@example.com")
                .displayName("Test " + role)
                .emailVerified(true)
                .role(role)
                .createdAt(Instant.now())
                .updatedAt(Instant.now())
                .build();
        appUserRepository.save(user);
        return jwtService.issueAccessToken(user);
    }

    @Test
    void anonymousCaller_seesOverallStatusOnly_noComponentDetail() throws Exception {
        mockMvc.perform(get("/actuator/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"))
                .andExpect(jsonPath("$.components").doesNotExist());
    }

    @Test
    void adminCaller_seesComponentDetail_includingMongo() throws Exception {
        String adminToken = tokenFor(Role.ADMIN);

        mockMvc.perform(get("/actuator/health")
                        .cookie(new jakarta.servlet.http.Cookie("ir_access", adminToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"))
                .andExpect(jsonPath("$.components.mongo.status").value("UP"));
    }

    @Test
    void regularUserCaller_stillDoesNotSeeComponentDetail() throws Exception {
        // This is the specific case that motivated adding `roles: ADMIN` — without it,
        // "when-authorized" authorizes any authenticated principal, not just admins,
        // so a regular player would previously have seen the same detail an admin does.
        String userToken = tokenFor(Role.USER);

        mockMvc.perform(get("/actuator/health")
                        .cookie(new jakarta.servlet.http.Cookie("ir_access", userToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"))
                .andExpect(jsonPath("$.components").doesNotExist());
    }
}
