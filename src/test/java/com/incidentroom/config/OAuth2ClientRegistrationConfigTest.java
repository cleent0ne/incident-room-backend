package com.incidentroom.config;

import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class OAuth2ClientRegistrationConfigTest {

    private OAuth2ClientRegistrationConfig configWith(String googleId, String googleSecret, String githubId, String githubSecret) {
        OAuth2ClientRegistrationConfig config = new OAuth2ClientRegistrationConfig();
        ReflectionTestUtils.setField(config, "frontendBaseUrl", "http://localhost:3000");
        ReflectionTestUtils.setField(config, "googleClientId", googleId);
        ReflectionTestUtils.setField(config, "googleClientSecret", googleSecret);
        ReflectionTestUtils.setField(config, "githubClientId", githubId);
        ReflectionTestUtils.setField(config, "githubClientSecret", githubSecret);
        return config;
    }

    @Test
    void nothingConfigured_returnsEmptyList_notNull() {
        OAuth2ClientRegistrationConfig config = configWith("", "", "", "");
        assertThat(config.buildConfiguredRegistrations()).isEmpty();
    }

    @Test
    void blankStrings_areTreatedAsUnconfigured_sameAsEmpty() {
        // A blank (whitespace-only) value is exactly as "unconfigured" as an empty string —
        // e.g. an env var accidentally set to a single space shouldn't half-activate a provider.
        OAuth2ClientRegistrationConfig config = configWith("  ", "  ", null, null);
        assertThat(config.buildConfiguredRegistrations()).isEmpty();
    }

    @Test
    void onlyClientIdSet_withNoSecret_doesNotCountAsConfigured() {
        OAuth2ClientRegistrationConfig config = configWith("some-id", "", "", "");
        assertThat(config.buildConfiguredRegistrations()).isEmpty();
    }

    @Test
    void googleOnly_producesExactlyOneRegistration() {
        OAuth2ClientRegistrationConfig config = configWith("google-id", "google-secret", "", "");

        List<ClientRegistration> registrations = config.buildConfiguredRegistrations();

        assertThat(registrations).hasSize(1);
        assertThat(registrations.get(0).getRegistrationId()).isEqualTo("google");
        assertThat(registrations.get(0).getClientId()).isEqualTo("google-id");
    }

    @Test
    void bothConfigured_producesBothRegistrations_independently() {
        OAuth2ClientRegistrationConfig config =
                configWith("google-id", "google-secret", "github-id", "github-secret");

        List<ClientRegistration> registrations = config.buildConfiguredRegistrations();

        assertThat(registrations).extracting(ClientRegistration::getRegistrationId)
                .containsExactlyInAnyOrder("google", "github");
    }

    @Test
    void googleRegistration_redirectUri_pointsAtFrontendOrigin_notBackend() {
        // The whole cookie architecture depends on this: the OAuth callback must land on the
        // frontend's origin (proxied through to the backend) so session cookies bind there,
        // not on the backend's own Railway domain. See the class javadoc.
        OAuth2ClientRegistrationConfig config = configWith("google-id", "google-secret", "", "");

        ClientRegistration google = config.buildConfiguredRegistrations().get(0);

        assertThat(google.getRedirectUri()).isEqualTo("http://localhost:3000/login/oauth2/code/{registrationId}");
    }

    @Test
    void githubRegistration_requestsUserEmailScope() {
        // CustomOAuth2UserService.resolveGithubEmail depends on this scope actually having
        // been requested — a regression here would silently break email resolution for GitHub
        // accounts with a private email, not fail loudly.
        OAuth2ClientRegistrationConfig config = configWith("", "", "github-id", "github-secret");

        ClientRegistration github = config.buildConfiguredRegistrations().get(0);

        assertThat(github.getScopes()).contains("user:email");
    }

    @Test
    void configuredProviderIds_nothingConfigured_returnsEmptyList() {
        OAuth2ClientRegistrationConfig config = configWith("", "", "", "");
        assertThat(config.configuredProviderIds()).isEmpty();
    }

    @Test
    void configuredProviderIds_matchesBuildConfiguredRegistrations() {
        // The /auth/oauth-providers endpoint's whole point is staying in sync with what
        // .oauth2Login() actually registers — these two derivations must never disagree.
        OAuth2ClientRegistrationConfig config =
                configWith("google-id", "google-secret", "github-id", "github-secret");

        assertThat(config.configuredProviderIds()).containsExactlyInAnyOrder("google", "github");
        assertThat(config.buildConfiguredRegistrations())
                .extracting(ClientRegistration::getRegistrationId)
                .containsExactlyInAnyOrderElementsOf(config.configuredProviderIds());
    }
}
