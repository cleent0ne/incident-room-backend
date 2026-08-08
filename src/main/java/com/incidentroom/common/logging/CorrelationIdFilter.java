package com.incidentroom.common.logging;

import com.incidentroom.security.JwtAuthenticationFilter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Assigns a correlation ID to every incoming request — either honoring an inbound
 * X-Correlation-Id header (e.g. from a load balancer / frontend trace) or generating a fresh one —
 * and puts it in the SLF4J MDC so every log line for this request carries it automatically.
 *
 * Runs first in the filter chain (Ordered.HIGHEST_PRECEDENCE) so it's available to every
 * subsequent filter and controller, including security filters that log auth failures.
 */
@Component
@Order(Integer.MIN_VALUE)
public class CorrelationIdFilter extends OncePerRequestFilter {

    public static final String CORRELATION_ID_HEADER = "X-Correlation-Id";
    public static final String MDC_KEY = "correlationId";

    /**
     * An inbound X-Correlation-Id is client-controlled and lands in both the MDC (every log line
     * for this request) and the echoed response header. Without a shape check, an attacker could
     * push arbitrary-length or control-character strings into structured logs — a plain-pattern
     * console logger (see logback-spring.xml's local-profile encoder) doesn't escape newlines the
     * way the JSON encoder does, so an unfiltered value can forge fake log lines. Restricting to a
     * conservative safe alphabet and a sane length covers UUIDs and typical trace-id formats
     * (ULID, W3C traceparent-style hex) without over-engineering this into a full trace-id parser.
     */
    private static final Pattern SAFE_CORRELATION_ID = Pattern.compile("^[A-Za-z0-9_-]{1,128}$");

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String correlationId = request.getHeader(CORRELATION_ID_HEADER);
        if (correlationId == null || !SAFE_CORRELATION_ID.matcher(correlationId).matches()) {
            correlationId = UUID.randomUUID().toString();
        }
        MDC.put(MDC_KEY, correlationId);
        response.setHeader(CORRELATION_ID_HEADER, correlationId);
        try {
            chain.doFilter(request, response);
        } finally {
            // Clear the WHOLE MDC, not just this key — downstream filters (e.g. JwtAuthenticationFilter,
            // which adds "userId") add their own entries, and thread pools reuse threads. Because this
            // filter wraps every other filter (Order.MIN_VALUE), this finally block runs last and is the
            // correct single place to guarantee nothing leaks into the next unrelated request.
            MDC.clear();
        }
    }
}
