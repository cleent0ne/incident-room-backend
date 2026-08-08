package com.incidentroom.security;

import com.incidentroom.common.exception.NotFoundException;
import com.incidentroom.domain.AppUser;
import com.incidentroom.service.AuthService;

import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.security.web.authentication.AuthenticationSuccessHandler;
import org.springframework.stereotype.Component;

import java.io.IOException;

/**
 * The OAuth2 dance itself (redirect to provider, handle callback, run CustomOAuth2UserService)
 * is entirely handled by Spring Security's filter chain with zero custom code. This handler is
 * the one seam where control comes back to us: issue the SAME session cookies a password login
 * would get (see AuthService.issueSession), then redirect to the frontend. No token is ever put
 * in the redirect URL — the cookie is already set on the response by the time this redirect happens.
 */
@Component
public class OAuth2AuthenticationSuccessHandler implements AuthenticationSuccessHandler {

    private final AuthService authService;
    private final String frontendBaseUrl;

    public OAuth2AuthenticationSuccessHandler(
            AuthService authService,
            @Value("${app.frontend-base-url}") String frontendBaseUrl) {
        this.authService = authService;
        this.frontendBaseUrl = frontendBaseUrl;
    }

    @Override
    public void onAuthenticationSuccess(HttpServletRequest request, HttpServletResponse response, Authentication authentication)
            throws IOException, ServletException {

        OAuth2User oauth2User = (OAuth2User) authentication.getPrincipal();
        String appUserId = oauth2User.getAttribute(CustomOAuth2UserService.APP_USER_ID_ATTRIBUTE);

        AppUser user = authService.userRepository().findById(appUserId)
                .orElseThrow(() -> new NotFoundException("User not found after OAuth2 authentication"));

        authService.issueSession(user, request, response);

        response.sendRedirect(frontendBaseUrl + "/auth/callback");
    }
}
