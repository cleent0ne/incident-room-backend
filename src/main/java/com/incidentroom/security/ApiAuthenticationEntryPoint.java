package com.incidentroom.security;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.stereotype.Component;

import java.io.IOException;

/**
 * Without this, Spring Security's default behavior for an unauthenticated request on a chain
 * that has oauth2Login configured is to redirect to an HTML login page (302 -> /login) — correct
 * for a browser-rendered app, wrong for a JSON API. Every protected endpoint under the main chain
 * (i.e. everything except /api/v1/admin/**, which has its own 404-masking entry point) should
 * return a clean 401 JSON body instead.
 */
@Component
public class ApiAuthenticationEntryPoint implements AuthenticationEntryPoint {

    @Override
    public void commence(HttpServletRequest request, HttpServletResponse response, AuthenticationException authException)
            throws IOException {
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType("application/json");
        response.getWriter().write("{\"code\":\"UNAUTHENTICATED\",\"message\":\"Authentication required.\"}");
    }
}
