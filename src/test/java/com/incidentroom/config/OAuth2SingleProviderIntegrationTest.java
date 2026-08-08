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

/**
 * Confirms partial configuration works: a deployment that has only gotten around to setting
 * up Google OAuth (not GitHub yet) gets a working Google login without needing both providers
 * configured at once. This is exactly the state a real rollout goes through — see
 * SecurityConfig.buildOAuth2Registrations(), which builds each provider's registration
 * independently rather than requiring all-or-nothing.
 */
class OAuth2SingleProviderIntegrationTest extends BaseIntegrationTest {

    @DynamicPropertySource
    static void oauthProperties(DynamicPropertyRegistry registry) {
        registry.add("GOOGLE_OAUTH_CLIENT_ID", () -> "test-google-client-id");
        registry.add("GOOGLE_OAUTH_CLIENT_SECRET", () -> "test-google-client-secret");
        // Deliberately left unset: GITHUB_OAUTH_CLIENT_ID / GITHUB_OAUTH_CLIENT_SECRET
    }

    @Autowired MockMvc mockMvc;

    @Test
    void configuredProvider_redirectsToTheRealProvider() throws Exception {
        mockMvc.perform(get("/oauth2/authorization/google"))
                .andExpect(status().is3xxRedirection())
                .andExpect(header().string("Location", containsString("accounts.google.com")))
                // The registered redirect_uri must point at the FRONTEND's origin (proxied
                // through to this backend via next.config.js), not this backend's own Railway
                // domain — see OAuth2ClientRegistrationConfig's class javadoc for why. This is
                // also exactly what has to be registered as the authorized redirect URI in the
                // Google Cloud Console.
                .andExpect(header().string("Location",
                        containsString("redirect_uri=http://localhost:3000/login/oauth2/code/google")));
    }
}

