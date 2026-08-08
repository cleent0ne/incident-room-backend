package com.incidentroom.security;

import com.incidentroom.common.logging.CorrelationIdFilter;
import com.incidentroom.config.SecurityConfig;

import io.jsonwebtoken.Claims;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import org.springframework.security.web.csrf.CsrfToken;

import java.io.IOException;
import java.util.List;
import java.util.Optional;

/**
 * Reads the ir_access HttpOnly cookie (never an Authorization header — see backend security
 * architecture doc §1.1 for why), validates it, and populates the SecurityContext.
 *
 * Deliberately permissive on failure: an absent/invalid/expired token just leaves the request
 * unauthenticated rather than short-circuiting here — authorization rules downstream (in
 * SecurityConfig) decide what an unauthenticated request is allowed to do. This keeps this filter
 * single-purpose and testable.
 */
@Component
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private final JwtService jwtService;

    public JwtAuthenticationFilter(JwtService jwtService) {
        this.jwtService = jwtService;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {

        CsrfToken csrfToken = (CsrfToken) request.getAttribute(CsrfToken.class.getName());
        if (csrfToken != null) {
            csrfToken.getToken();
        }

        extractCookie(request, CookieService.ACCESS_COOKIE)
                .flatMap(jwtService::parse)
                .ifPresent(this::authenticate);

        chain.doFilter(request, response);
    }

    private void authenticate(Claims claims) {
        String userId = claims.getSubject();
        String role = claims.get("role", String.class);

        List<GrantedAuthority> authorities = List.of(new SimpleGrantedAuthority("ROLE_" + role));
        var authentication = new UsernamePasswordAuthenticationToken(userId, null, authorities);
        SecurityContextHolder.getContext().setAuthentication(authentication);

        // Attach userId to logs for this request now that we know who it is — pairs with the
        // correlation ID already set by CorrelationIdFilter, so every log line is traceable both
        // to "which request" and "which user" without either filter needing to know about the other.
        MDC.put("userId", userId);
    }

    private Optional<String> extractCookie(HttpServletRequest request, String name) {
        if (request.getCookies() == null) {
            return Optional.empty();
        }
        for (Cookie cookie : request.getCookies()) {
            if (cookie.getName().equals(name)) {
                return Optional.of(cookie.getValue());
            }
        }
        return Optional.empty();
    }
}
