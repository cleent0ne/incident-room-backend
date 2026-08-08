package com.incidentroom.security;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.Bucket;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.util.AntPathMatcher;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * In-memory rate limiting using Bucket4j per-IP buckets, backed by a bounded
 * Caffeine cache.
 *
 * <h3>IP Resolution</h3>
 * We read {@code request.getRemoteAddr()} only — never the raw
 * {@code X-Forwarded-For} header.
 * In production (profile {@code prod}), Tomcat's RemoteIp valve (configured via
 * {@code server.forward-headers-strategy=native} and
 * {@code server.tomcat.remoteip.internal-proxies})
 * pre-processes XFF and updates {@code remoteAddr} to the real client IP before
 * any servlet sees
 * the request, but ONLY if the request arrived from a trusted proxy CIDR. A
 * direct caller
 * spoofing XFF is not in the trusted CIDR list and Tomcat ignores the header,
 * so {@code remoteAddr}
 * is the raw TCP peer — the attacker's own address. This is the correct
 * protection.
 *
 * <h3>Bucket map bounding</h3>
 * Buckets expire from the Caffeine cache after the longest refill period in
 * RULES (currently 1 hour).
 * This prevents an adversary from exhausting JVM heap by cycling through
 * fictitious IP addresses.
 *
 * <h3>Single-instance note</h3>
 * Bucket state is not shared across instances. Move to a shared store (Redis +
 * Bucket4j-Redis) when
 * scaling beyond one replica — see the backend security architecture doc §5.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10) // after CorrelationIdFilter, before Spring Security's chain
public class RateLimitFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(RateLimitFilter.class);
    private static final AntPathMatcher PATH_MATCHER = new AntPathMatcher();

    /**
     * Rules are evaluated in declaration order; the first match wins.
     * Keep more-specific paths (e.g. /attempts/claim) BEFORE wildcard patterns
     * (e.g. /attempts).
     * (method, path pattern) → (capacity, refill period)
     */
    private static final List<LimitRule> RULES = List.of(
            // Auth endpoints — tighter limits; account enumeration/brute-force risk
            new LimitRule("POST", "/api/v1/auth/login", 10, Duration.ofMinutes(1)),
            new LimitRule("POST", "/api/v1/auth/register", 5, Duration.ofHours(1)),
            new LimitRule("POST", "/api/v1/auth/resend-verification", 3, Duration.ofHours(1)),
            new LimitRule("POST", "/api/v1/auth/forgot-password", 3, Duration.ofHours(1)),
            new LimitRule("POST", "/api/v1/auth/reset-password", 5, Duration.ofMinutes(1)),
            new LimitRule("POST", "/api/v1/auth/refresh", 30, Duration.ofHours(1)),
            new LimitRule("POST", "/api/v1/auth/logout", 10, Duration.ofMinutes(1)),
            new LimitRule("POST", "/api/v1/auth/logout-all", 5, Duration.ofMinutes(1)),
            // Claim: must be ordered before the /attempts wildcard below.
            // 5/min is tight enough to stop token-enumeration scripts while still allowing
            // a legitimate user to retry a batch immediately after login.
            new LimitRule("POST", "/api/v1/attempts/claim", 5, Duration.ofMinutes(1)),
            // Attempt sub-routes
            new LimitRule("POST", "/api/v1/attempts", 30, Duration.ofMinutes(1)),
            new LimitRule("POST", "/api/v1/attempts/*/actions", 60, Duration.ofMinutes(1)),
            new LimitRule("POST", "/api/v1/attempts/*/resolve", 60, Duration.ofMinutes(1)),
            new LimitRule("PATCH", "/api/v1/attempts/*/reflection", 20, Duration.ofMinutes(1)));

    /**
     * Longest refill window across all rules. Buckets are evicted from the cache
     * after this TTL so
     * that the map stays bounded regardless of how many unique IPs the server has
     * seen.
     */
    private static final Duration BUCKET_TTL = RULES.stream()
            .map(r -> r.period)
            .max(Duration::compareTo)
            .orElse(Duration.ofHours(1));

    /**
     * Bounded Caffeine cache: entries expire after {@link #BUCKET_TTL} of
     * inactivity.
     * Maximum 50 000 entries — a generous ceiling for a single-instance deployment
     * that still
     * puts a hard bound on memory usage (~a few MB).
     */
    private final Cache<String, Bucket> buckets = Caffeine.newBuilder()
            .maximumSize(50_000)
            .expireAfterAccess(BUCKET_TTL.toSeconds(), TimeUnit.SECONDS)
            .build();

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {

        LimitRule matchedRule = RULES.stream()
                .filter(rule -> (rule.httpMethod == null || rule.httpMethod.equalsIgnoreCase(request.getMethod()))
                        && PATH_MATCHER.match(rule.pathPattern, request.getRequestURI()))
                .findFirst()
                .orElse(null);

        if (matchedRule == null) {
            chain.doFilter(request, response);
            return;
        }

        // Use the already-resolved remote address — Tomcat's RemoteIp valve has already
        // validated and unwrapped X-Forwarded-For in production; no manual header
        // parsing here.
        String clientIp = request.getRemoteAddr();
        String bucketKey = matchedRule.httpMethod + ":" + matchedRule.pathPattern + ":" + clientIp;
        Bucket bucket = buckets.get(bucketKey, k -> newBucket(matchedRule));

        if (bucket.tryConsume(1)) {
            chain.doFilter(request, response);
        } else {
            log.warn("Rate limit exceeded: rule={} ip={}", matchedRule.pathPattern, clientIp);
            response.setStatus(429);
            response.setContentType("application/json");
            response.getWriter().write(
                    "{\"code\":\"RATE_LIMITED\",\"message\":\"Too many requests. Please slow down and try again shortly.\"}");
        }
    }

    private Bucket newBucket(LimitRule rule) {
        Bandwidth limit = Bandwidth.classic(rule.capacity,
                io.github.bucket4j.Refill.intervally(rule.capacity, rule.period));
        return Bucket.builder().addLimit(limit).build();
    }

    private record LimitRule(String httpMethod, String pathPattern, int capacity, Duration period) {
        public LimitRule(String pathPattern, int capacity, Duration period) {
            this(null, pathPattern, capacity, period);
        }
    }
}
