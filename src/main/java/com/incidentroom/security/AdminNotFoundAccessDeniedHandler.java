package com.incidentroom.security;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.stereotype.Component;

import java.io.IOException;

/**
 * For authenticated-but-wrong-role requests to /api/v1/admin/** — e.g. a logged-in regular USER
 * probing for admin routes — returns the same 404 as AdminNotFoundEntryPoint, so authenticated
 * non-admins and unauthenticated callers see an identical response either way.
 */
@Component
public class AdminNotFoundAccessDeniedHandler implements AccessDeniedHandler {

    private static final Logger log = LoggerFactory.getLogger(AdminNotFoundAccessDeniedHandler.class);

    @Override
    public void handle(HttpServletRequest request, HttpServletResponse response, AccessDeniedException accessDeniedException)
            throws IOException {
        log.warn("admin_access_denied: authenticated non-admin request to {} {}", request.getMethod(), request.getRequestURI());
        AdminNotFoundEntryPoint.respondNotFound(response);
    }
}
