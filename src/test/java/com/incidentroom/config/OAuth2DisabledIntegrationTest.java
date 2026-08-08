package com.incidentroom.config;

import com.incidentroom.BaseIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Regression test for a real, if smaller-than-first-suspected, bug. A previous version of
 * {@code OAuth2ClientRegistrationConfig} exposed a {@code @Bean ClientRegistrationRepository}
 * that fell back to {@code registrationId -> null} when no providers were configured — which
 * did keep the application booting (Spring Security's {@code .oauth2Login()} DSL only requires
 * the bean to exist, not to be non-empty), but meant that hitting
 * {@code /oauth2/authorization/google} with nothing configured resolved "google" to null and
 * the authorization request resolver threw, producing a 500 instead of a clean, expected 401.
 *
 * SecurityConfig now calls {@code OAuth2ClientRegistrationConfig.buildConfiguredRegistrations()}
 * directly and skips {@code .oauth2Login()} entirely when it's empty, so an unconfigured OAuth
 * route behaves exactly like any other protected, unauthenticated route.
 */
class OAuth2DisabledIntegrationTest extends BaseIntegrationTest {

    @Autowired MockMvc mockMvc;

    @Test
    void unconfiguredOAuthProvider_requiresAuthentication_ratherThanErroring() throws Exception {
        // With .oauth2Login() skipped entirely, /oauth2/authorization/google isn't handled by
        // any OAuth2-specific filter — it falls through to the ordinary
        // "anyRequest().authenticated()" rule and gets the same 401 JSON body any other
        // protected, unauthenticated request would get. NOT a redirect to Google, and NOT a
        // 500 from a null ClientRegistration — the app just doesn't have this feature active.
        mockMvc.perform(get("/oauth2/authorization/google"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
    }

    @Test
    void unconfiguredOAuthProvider_github_alsoRequiresAuthentication() throws Exception {
        mockMvc.perform(get("/oauth2/authorization/github"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void passwordAuthEndpoints_areCompletelyUnaffectedByOAuthBeingDisabled() throws Exception {
        // The whole point of making OAuth2 login conditional: password auth (the primary
        // login path) must work identically whether or not OAuth is configured. A malformed
        // login request should get a normal validation error, not a 500 from some OAuth-related
        // context issue leaking through.
        mockMvc.perform(get("/api/v1/scenarios"))
                .andExpect(status().isOk());
    }
}
