package com.incidentroom.service;

import com.incidentroom.domain.RefreshToken;
import com.incidentroom.repository.RefreshTokenRepository;
import com.incidentroom.security.CookieService;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Service
public class RefreshTokenService {

    private static final Logger log = LoggerFactory.getLogger(RefreshTokenService.class);

    private final RefreshTokenRepository repository;
    private final CookieService cookieService;
    private final int refreshTokenTtlDays;

    public RefreshTokenService(
            RefreshTokenRepository repository,
            CookieService cookieService,
            @Value("${app.jwt.refresh-token-ttl-days}") int refreshTokenTtlDays) {
        this.repository = repository;
        this.cookieService = cookieService;
        this.refreshTokenTtlDays = refreshTokenTtlDays;
    }

    public record IssuedToken(String plaintextToken, RefreshToken persisted) {}

    /** Issues the first token in a new rotation family — called on login/register-verify. */
    public IssuedToken issueNewFamily(String userId, String userAgent, String ipAddress) {
        return issue(userId, UUID.randomUUID().toString(), userAgent, ipAddress);
    }

    private IssuedToken issue(String userId, String familyId, String userAgent, String ipAddress) {
        String plaintext = cookieService.generateOpaqueToken();
        RefreshToken token = RefreshToken.builder()
                .userId(userId)
                .tokenHash(hash(plaintext))
                .familyId(familyId)
                .createdAt(Instant.now())
                .expiresAt(Instant.now().plus(refreshTokenTtlDays, ChronoUnit.DAYS))
                .userAgent(userAgent)
                .ipAddress(ipAddress)
                .build();
        repository.save(token);
        return new IssuedToken(plaintext, token);
    }

    public enum RotationResult { SUCCESS, INVALID, EXPIRED, REUSE_DETECTED }

    public record RotationOutcome(RotationResult result, String userId, IssuedToken newToken) {}

    /**
     * Validates the presented refresh token and, if valid, rotates it: the old one is marked
     * revoked, a new one is issued in the same family. If the presented token was ALREADY revoked
     * (i.e. someone is replaying a token that was already rotated away), that's treated as reuse —
     * a strong signal of theft — and the entire family is revoked, forcing full re-authentication.
     */
    public RotationOutcome rotate(String plaintextToken, String userAgent, String ipAddress) {
        String presentedHash = hash(plaintextToken);
        Optional<RefreshToken> maybeToken = repository.findByTokenHash(presentedHash);

        if (maybeToken.isEmpty()) {
            return new RotationOutcome(RotationResult.INVALID, null, null);
        }

        RefreshToken existing = maybeToken.get();

        if (existing.getRevokedAt() != null) {
            log.warn("Refresh token reuse detected for user={} family={} — revoking entire family",
                    existing.getUserId(), existing.getFamilyId());
            revokeFamily(existing.getFamilyId());
            return new RotationOutcome(RotationResult.REUSE_DETECTED, existing.getUserId(), null);
        }

        if (existing.getExpiresAt().isBefore(Instant.now())) {
            return new RotationOutcome(RotationResult.EXPIRED, existing.getUserId(), null);
        }

        IssuedToken newToken = issue(existing.getUserId(), existing.getFamilyId(), userAgent, ipAddress);
        existing.setRevokedAt(Instant.now());
        existing.setReplacedByTokenHash(newToken.persisted().getTokenHash());
        repository.save(existing);

        return new RotationOutcome(RotationResult.SUCCESS, existing.getUserId(), newToken);
    }

    public void revokeFamily(String familyId) {
        List<RefreshToken> active = repository.findByFamilyIdAndRevokedAtIsNull(familyId);
        Instant now = Instant.now();
        active.forEach(t -> t.setRevokedAt(now));
        repository.saveAll(active);
    }

    /** "Log out everywhere" — revokes every active refresh token for a user, across all families/devices. */
    public void revokeAllForUser(String userId) {
        List<RefreshToken> active = repository.findByUserIdAndRevokedAtIsNull(userId);
        Instant now = Instant.now();
        active.forEach(t -> t.setRevokedAt(now));
        repository.saveAll(active);
    }

    public void revokeSingle(String plaintextToken) {
        repository.findByTokenHash(hash(plaintextToken))
                .ifPresent(t -> {
                    t.setRevokedAt(Instant.now());
                    repository.save(t);
                });
    }

    private String hash(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is a required JDK algorithm; this should never happen", e);
        }
    }
}
