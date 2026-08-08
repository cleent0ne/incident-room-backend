package com.incidentroom.config;

import com.incidentroom.BaseIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Confirms both providers activate together when both are fully configured. */
class OAuth2BothProvidersIntegrationTest extends BaseIntegrationTest {

    @DynamicPropertySource
    static void oauthProperties(DynamicPropertyRegistry registry) {
        registry.add("GOOGLE_OAUTH_CLIENT_ID", () -> "test-google-client-id");
        registry.add("GOOGLE_OAUTH_CLIENT_SECRET", () -> "test-google-client-secret");
        registry.add("GITHUB_OAUTH_CLIENT_ID", () -> "test-github-client-id");
        registry.add("GITHUB_OAUTH_CLIENT_SECRET", () -> "test-github-client-secret");
    }

    @Autowired MockMvc mockMvc;

    @Test
    void google_redirectsToGoogle() throws Exception {
        mockMvc.perform(get("/oauth2/authorization/google"))
                .andExpect(status().is3xxRedirection())
                .andExpect(header().string("Location", containsString("accounts.google.com")));
    }

    @Test
    void github_redirectsToGithub() throws Exception {
        mockMvc.perform(get("/oauth2/authorization/github"))
                .andExpect(status().is3xxRedirection())
                .andExpect(header().string("Location", containsString("github.com/login/oauth/authorize")));
    }

    @Test
    void passwordAuthStillWorks_alongsideOAuth() throws Exception {
        // OAuth being enabled must never crowd out or interfere with the primary login path.
        mockMvc.perform(get("/api/v1/scenarios"))
                .andExpect(status().isOk());
    }
}
