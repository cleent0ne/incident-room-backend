package com.incidentroom.domain;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;

/**
 * Short-lived by design — a TTL index on expiresAt (configured in MongoIndexConfig) means
 * expired codes are automatically reaped by MongoDB, no cleanup job required.
 *
 * codeHash is a SHA-256 hash of the 6-digit code; the plaintext code is never persisted anywhere,
 * exactly like a password.
 */
@Document(collection = "email_verifications")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class EmailVerification {

    @Id
    private String id;

    @Indexed
    private String userId;

    @lombok.ToString.Exclude
    private String codeHash;

    // expireAfterSeconds = 0 means "delete once the current time passes this document's own
    // expiresAt value" — MongoDB handles cleanup automatically, no scheduled job required.
    @Indexed(expireAfterSeconds = 0)
    private Instant expiresAt;

    @Builder.Default
    private int attempts = 0;

    private Instant createdAt;
}
