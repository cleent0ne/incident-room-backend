package com.incidentroom.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.config.oauth2.client.CommonOAuth2Provider;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Builds OAuth2 client registrations for whichever providers (Google, GitHub) actually have
 * credentials configured — zero, one, or both, independently.
 *
 * WHY THIS EXISTS AS ITS OWN CLASS, NOT spring.security.oauth2.client.registration.* PROPERTIES:
 * Spring Boot's built-in OAuth2 client autoconfiguration treats the mere PRESENCE of a
 * registration block under that property namespace as "this provider is configured" — even if
 * its client-id resolves to an empty string — and hard-fails application startup with "Client id
 * of registration 'x' must not be empty." Local dev, tests, and even a first production deploy
 * (before the Google/GitHub OAuth apps are registered) are all legitimate states where OAuth2
 * simply isn't configured yet, and the app should boot normally with those login options
 * unavailable rather than refuse to start entirely — so registrations are built by hand here
 * instead, reading the raw GOOGLE_OAUTH_CLIENT_ID-style env vars directly.
 *
 * NOT exposed as a {@code @Bean ClientRegistrationRepository}: an earlier version of this class
 * did that, falling back to a {@code registrationId -> null} repository when nothing was
 * configured so Spring Security's {@code .oauth2Login()} DSL (which requires a
 * ClientRegistrationRepository to exist) would always find a bean and the app would always boot.
 * That worked for booting, but had a sharp edge: hitting /oauth2/authorization/google with no
 * providers configured would resolve "google" to null and Spring Security's authorization
 * request resolver throws on a null registration — a 500, not a clean 401. SecurityConfig now
 * calls {@link #buildConfiguredRegistrations()} directly and skips {@code .oauth2Login()}
 * entirely when the result is empty, so an unconfigured OAuth route behaves like any other
 * protected, unauthenticated route (401) instead of erroring.
 *
 * WHY redirectUri IS THE FRONTEND'S ORIGIN, NOT "{baseUrl}": the frontend's own Next.js server
 * proxies /oauth2/** and /login/oauth2/** through to this backend (see next.config.js) so that,
 * from the browser's perspective, the entire OAuth dance stays same-origin with the frontend —
 * which is what lets the session cookies this backend sets during the callback actually bind to
 * the frontend's origin and be visible to subsequent same-origin /api/v1/** calls. Spring
 * Security's "{baseUrl}" placeholder would instead resolve from the incoming (proxied) request's
 * Host/X-Forwarded-* headers, which depends on exactly how those headers survive the proxy hop —
 * not something worth trusting implicitly when the correct value (app.frontend-base-url) is
 * already known and configured explicitly. This also means: Google/GitHub's OAuth app settings
 * must have the FRONTEND's origin registered as the authorized redirect URI
 * (https://<frontend-domain>/login/oauth2/code/google), not the backend's Railway domain — see
 * the README's OAuth setup section.
 */
@Component
public class OAuth2ClientRegistrationConfig {

    @Value("${app.frontend-base-url}")
    private String frontendBaseUrl;

    @Value("${GOOGLE_OAUTH_CLIENT_ID:}")
    private String googleClientId;

    @Value("${GOOGLE_OAUTH_CLIENT_SECRET:}")
    private String googleClientSecret;

    @Value("${GITHUB_OAUTH_CLIENT_ID:}")
    private String githubClientId;

    @Value("${GITHUB_OAUTH_CLIENT_SECRET:}")
    private String githubClientSecret;

    /**
     * @return a registration for each provider that has both a client-id and a client-secret
     * configured — possibly empty (no providers configured), never null. See
     * {@link com.incidentroom.config.SecurityConfig#mainFilterChain} for how an empty result is
     * handled: {@code .oauth2Login()} is skipped entirely rather than configured with an empty
     * {@link org.springframework.security.oauth2.client.registration.ClientRegistrationRepository}.
     */
    public List<ClientRegistration> buildConfiguredRegistrations() {
        List<ClientRegistration> registrations = new ArrayList<>();

        if (isConfigured(googleClientId, googleClientSecret)) {
            registrations.add(CommonOAuth2Provider.GOOGLE.getBuilder("google")
                    .clientId(googleClientId)
                    .clientSecret(googleClientSecret)
                    .scope("openid", "email", "profile")
                    .redirectUri(frontendBaseUrl + "/login/oauth2/code/{registrationId}")
                    .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                    .build());
        }

        if (isConfigured(githubClientId, githubClientSecret)) {
            registrations.add(CommonOAuth2Provider.GITHUB.getBuilder("github")
                    .clientId(githubClientId)
                    .clientSecret(githubClientSecret)
                    // user:email (not the nonexistent "user:emails") is what actually grants access
                    // to the account's email address — see CustomOAuth2UserService.resolveGithubEmail,
                    // which depends on this scope for the common case where email isn't public.
                    .scope("read:user", "user:email")
                    .redirectUri(frontendBaseUrl + "/login/oauth2/code/{registrationId}")
                    .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                    .build());
        }

        return registrations;
    }

    /**
     * Lightweight companion to {@link #buildConfiguredRegistrations()} — just the registration
     * IDs, for AuthController's /auth/oauth-providers endpoint. Lets the frontend ask "which
     * providers are actually usable right now" instead of hardcoding button visibility from a
     * build-time env var, which drifts from the backend's actual configuration (e.g. a preview
     * deploy that has GOOGLE_OAUTH_CLIENT_ID set but not GITHUB_OAUTH_CLIENT_ID). Deliberately
     * does not build full ClientRegistration objects (no need to touch client secrets for this).
     */
    public List<String> configuredProviderIds() {
        List<String> ids = new ArrayList<>();
        if (isConfigured(googleClientId, googleClientSecret)) {
            ids.add("google");
        }
        if (isConfigured(githubClientId, githubClientSecret)) {
            ids.add("github");
        }
        return ids;
    }

    private boolean isConfigured(String clientId, String clientSecret) {
        return clientId != null && !clientId.isBlank() && clientSecret != null && !clientSecret.isBlank();
    }
}
