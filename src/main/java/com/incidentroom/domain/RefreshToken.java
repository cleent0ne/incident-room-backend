package com.incidentroom.domain;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;

/**
 * A refresh token is deliberately NOT a self-contained JWT — it's a random opaque value,
 * stored here hashed (never in plaintext), so it can actually be revoked (logout, password
 * change, suspected theft) rather than living until its JWT expiry regardless of what happens.
 *
 * Rotation: every /auth/refresh call issues a brand new token and marks this one revoked +
 * linked via replacedByTokenHash. If a revoked token is ever presented again, that's reuse —
 * a strong signal of theft — and the whole family descending from the original login should
 * be revoked, forcing re-authentication.
 */
@Document(collection = "refresh_tokens")
@CompoundIndex(name = "user_lookup_idx", def = "{'userId': 1, 'revokedAt': 1}")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RefreshToken {

    @Id
    private String id;

    private String userId;

    @Indexed(unique = true)
    @lombok.ToString.Exclude
    private String tokenHash;

    /** Root of this token's rotation chain — every token issued from one login shares this value. */
    private String familyId;

    private Instant createdAt;

    // TTL index — MongoDB reaps the document once expiresAt has passed. Revoked-but-not-yet-expired
    // tokens are kept until their natural expiry rather than deleted immediately on revoke, which
    // preserves a short forensic trail (e.g. reviewing a reuse-detection event) without a separate audit collection.
    @Indexed(expireAfterSeconds = 0)
    private Instant expiresAt;

    private Instant revokedAt;

    @lombok.ToString.Exclude
    private String replacedByTokenHash;

    private String userAgent;

    private String ipAddress;
}
