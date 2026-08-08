package com.incidentroom.security;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.stereotype.Component;

import java.io.IOException;

/**
 * For unauthenticated requests to /api/v1/admin/**, returns 404 instead of the default 401.
 * See backend security architecture doc §6 for the full rationale: this denies an attacker the
 * "this route exists, you're just not allowed in" signal that a 401/403 gives away. Real
 * authorization (hasRole("ADMIN")) still does all the actual access control underneath — this
 * is a cheap, additional layer of reconnaissance resistance, not a substitute for it.
 */
@Component
public class AdminNotFoundEntryPoint implements AuthenticationEntryPoint {

    private static final Logger log = LoggerFactory.getLogger(AdminNotFoundEntryPoint.class);

    @Override
    public void commence(HttpServletRequest request, HttpServletResponse response, AuthenticationException authException)
            throws IOException {
        log.warn("admin_access_denied: unauthenticated request to {} {}", request.getMethod(), request.getRequestURI());
        respondNotFound(response);
    }

    static void respondNotFound(HttpServletResponse response) throws IOException {
        response.setStatus(HttpServletResponse.SC_NOT_FOUND);
        response.setContentType("application/json");
        // Identical shape to a real 404 elsewhere in the app — a differently-shaped body here
        // would itself be a small information leak that this endpoint is "special".
        response.getWriter().write("{\"code\":\"NOT_FOUND\",\"message\":\"The requested resource was not found.\"}");
    }
}
