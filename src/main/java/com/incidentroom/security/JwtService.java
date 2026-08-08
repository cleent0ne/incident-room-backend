package com.incidentroom.security;

import com.incidentroom.domain.AppUser;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Date;
import java.util.Optional;

/**
 * Access tokens only — refresh tokens are deliberately NOT JWTs (see RefreshToken.java for why).
 * Keeps the claim set minimal on purpose: nothing here that would be a problem if the (signed,
 * but still decodable) payload were inspected by whoever holds the cookie.
 */
@Service
public class JwtService {

    private final SecretKey signingKey;
    private final int accessTokenTtlMinutes;

    public JwtService(
            @Value("${app.jwt.secret}") String secret,
            @Value("${app.jwt.access-token-ttl-minutes}") int accessTokenTtlMinutes) {
        // HS256 with a sufficiently long secret is the right call at this stage — see the backend
        // security architecture doc for why RS256 is deliberately deferred until it's actually needed.
        this.signingKey = Keys.hmacShaKeyFor(secret.getBytes());
        this.accessTokenTtlMinutes = accessTokenTtlMinutes;
    }

    public String issueAccessToken(AppUser user) {
        Instant now = Instant.now();
        return Jwts.builder()
                .subject(user.getId())
                .claim("role", user.getRole().name())
                .claim("emailVerified", user.isEmailVerified())
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plus(accessTokenTtlMinutes, ChronoUnit.MINUTES)))
                .signWith(signingKey)
                .compact();
    }

    public Optional<Claims> parse(String token) {
        try {
            Claims claims = Jwts.parser()
                    .verifyWith(signingKey)
                    .build()
                    .parseSignedClaims(token)
                    .getPayload();
            return Optional.of(claims);
        } catch (JwtException | IllegalArgumentException e) {
            // Expired, malformed, or tampered — all treated identically as "not authenticated".
            // Deliberately not logged at WARN here to avoid noise; a genuinely suspicious pattern
            // (e.g. a spike of invalid tokens) is better caught via the auth-failure metric, not per-log-line.
            return Optional.empty();
        }
    }

    public int getAccessTokenTtlSeconds() {
        return accessTokenTtlMinutes * 60;
    }
}
