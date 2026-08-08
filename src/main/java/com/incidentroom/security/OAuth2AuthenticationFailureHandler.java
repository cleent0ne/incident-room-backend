package com.incidentroom.security;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.authentication.AuthenticationFailureHandler;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

/**
 * Without this, an OAuth2 failure (provider rejected the login, CustomOAuth2UserService threw —
 * e.g. an unverified Google email or a GitHub account with no public email) falls through to
 * Spring Security's default failure handling: an HTML redirect to {@code /login?error} on THIS
 * backend, which has no such page since it's a JSON API with no server-rendered UI. The user
 * would land on a dead end with no way back to the app.
 *
 * Mirrors {@link OAuth2AuthenticationSuccessHandler}'s shape: redirect to the frontend, not a
 * page on this server. Deliberately sends a fixed, generic error code rather than
 * exception.getMessage() — this app never leaks internal exception detail to the client
 * (see server.error.include-message: never in application.yml), and an OAuth failure reason
 * is exactly the kind of detail that shouldn't round-trip through a redirect URL either.
 */
@Component
public class OAuth2AuthenticationFailureHandler implements AuthenticationFailureHandler {

    private final String frontendBaseUrl;

    public OAuth2AuthenticationFailureHandler(@Value("${app.frontend-base-url}") String frontendBaseUrl) {
        this.frontendBaseUrl = frontendBaseUrl;
    }

    @Override
    public void onAuthenticationFailure(HttpServletRequest request, HttpServletResponse response, AuthenticationException exception)
            throws IOException {
        String code = URLEncoder.encode("oauth_failed", StandardCharsets.UTF_8);
        response.sendRedirect(frontendBaseUrl + "/login?error=" + code);
    }
}
