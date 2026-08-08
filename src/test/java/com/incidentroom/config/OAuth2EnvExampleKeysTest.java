package com.incidentroom.config;

import com.incidentroom.BaseIntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasItems;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Tests that the exact OAuth keys defined in .env.example are properly recognized by
 * OAuth2ClientRegistrationConfig and Spring Security endpoints.
 */
class OAuth2EnvExampleKeysTest extends BaseIntegrationTest {

    @DynamicPropertySource
    static void envExampleOAuthProperties(DynamicPropertyRegistry registry) {
        // Values straight from incident-room-backend/.env.example
        registry.add("GOOGLE_OAUTH_CLIENT_ID", () -> "666758220263-gn8aovs4d4l95g50ent901nchamjveqa.apps.googleusercontent.com");
        registry.add("GOOGLE_OAUTH_CLIENT_SECRET", () -> "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");
        registry.add("GITHUB_OAUTH_CLIENT_ID", () -> "Ov23liHsgZHPqCbqKNGJ");
        registry.add("GITHUB_OAUTH_CLIENT_SECRET", () -> "5c97ff265ee8863c262be51d0b03c373c0ff18f1");
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private OAuth2ClientRegistrationConfig oAuth2ClientRegistrationConfig;

    @Test
    @DisplayName("Verify configuredProviderIds returns both google and github for env.example keys")
    void configuredProviderIds_recognizesBothProvidersFromEnvExample() {
        var providerIds = oAuth2ClientRegistrationConfig.configuredProviderIds();
        org.assertj.core.api.Assertions.assertThat(providerIds)
                .containsExactlyInAnyOrder("google", "github");
    }

    @Test
    @DisplayName("GET /api/v1/auth/oauth-providers returns google and github")
    void oauthProvidersEndpoint_returnsConfiguredProviders() throws Exception {
        mockMvc.perform(get("/api/v1/auth/oauth-providers"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.providers", hasItems("google", "github")));
    }

    @Test
    @DisplayName("GET /oauth2/authorization/google initiates Google OAuth with client ID from env.example")
    void googleAuthEndpoint_redirectsWithEnvExampleClientId() throws Exception {
        mockMvc.perform(get("/oauth2/authorization/google"))
                .andExpect(status().is3xxRedirection())
                .andExpect(header().string("Location", containsString("accounts.google.com")))
                .andExpect(header().string("Location", containsString("client_id=666758220263-gn8aovs4d4l95g50ent901nchamjveqa.apps.googleusercontent.com")))
                .andExpect(header().string("Location", containsString("redirect_uri=http://localhost:3000/login/oauth2/code/google")));
    }

    @Test
    @DisplayName("GET /oauth2/authorization/github initiates GitHub OAuth with client ID from env.example")
    void githubAuthEndpoint_redirectsWithEnvExampleClientId() throws Exception {
        mockMvc.perform(get("/oauth2/authorization/github"))
                .andExpect(status().is3xxRedirection())
                .andExpect(header().string("Location", containsString("github.com/login/oauth/authorize")))
                .andExpect(header().string("Location", containsString("client_id=Ov23liHsgZHPqCbqKNGJ")))
                .andExpect(header().string("Location", containsString("redirect_uri=http://localhost:3000/login/oauth2/code/github")));
    }
}
