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
 * Short-lived password reset tokens — TTL index on expiresAt means expired tokens
 * are automatically reaped by MongoDB.
 *
 * tokenHash is a SHA-256 hash of the reset code/token; plaintext token is never stored.
 */
@Document(collection = "password_reset_tokens")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PasswordResetToken {

    @Id
    private String id;

    @Indexed
    private String userId;

    @lombok.ToString.Exclude
    private String tokenHash;

    @Indexed(expireAfterSeconds = 0)
    private Instant expiresAt;

    @Builder.Default
    private int attempts = 0;

    private Instant createdAt;
}
