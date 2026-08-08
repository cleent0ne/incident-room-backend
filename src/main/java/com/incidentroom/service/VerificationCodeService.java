package com.incidentroom.service;

import com.incidentroom.common.exception.TooManyRequestsException;
import com.incidentroom.domain.EmailVerification;
import com.incidentroom.repository.EmailVerificationRepository;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HexFormat;
import java.util.Optional;

@Service
public class VerificationCodeService {

    private final EmailVerificationRepository repository;
    private final SecureRandom random = new SecureRandom();
    private final int codeTtlMinutes;
    private final int maxAttempts;
    private final int resendCooldownMinutes;

    public VerificationCodeService(
            EmailVerificationRepository repository,
            @Value("${app.email-verification.code-ttl-minutes}") int codeTtlMinutes,
            @Value("${app.email-verification.max-attempts}") int maxAttempts,
            @Value("${app.email-verification.resend-cooldown-minutes}") int resendCooldownMinutes) {
        this.repository = repository;
        this.codeTtlMinutes = codeTtlMinutes;
        this.maxAttempts = maxAttempts;
        this.resendCooldownMinutes = resendCooldownMinutes;
    }

    /** Generates a fresh 6-digit code, invalidating any prior unexpired code for this user, and returns the plaintext code to send. */
    public String issueNewCode(String userId) {
        Optional<EmailVerification> existing = repository.findTopByUserIdOrderByCreatedAtDesc(userId);
        if (existing.isPresent()) {
            Instant cooldownEndsAt = existing.get().getCreatedAt().plus(resendCooldownMinutes, ChronoUnit.MINUTES);
            if (Instant.now().isBefore(cooldownEndsAt)) {
                throw new TooManyRequestsException("Please wait before requesting another verification code.");
            }
        }

        repository.deleteByUserId(userId); // one active code per user at a time — keeps the model simple and correct
        String code = String.format("%06d", random.nextInt(1_000_000));
        EmailVerification verification = EmailVerification.builder()
                .userId(userId)
                .codeHash(hash(code))
                .expiresAt(Instant.now().plus(codeTtlMinutes, ChronoUnit.MINUTES))
                .attempts(0)
                .createdAt(Instant.now())
                .build();
        repository.save(verification);
        return code;
    }

    public enum VerificationResult { SUCCESS, INVALID_CODE, EXPIRED, TOO_MANY_ATTEMPTS, NO_PENDING_CODE }

    public VerificationResult verify(String userId, String submittedCode) {
        Optional<EmailVerification> maybeVerification = repository.findTopByUserIdOrderByCreatedAtDesc(userId);
        if (maybeVerification.isEmpty()) {
            return VerificationResult.NO_PENDING_CODE;
        }

        EmailVerification verification = maybeVerification.get();

        if (verification.getAttempts() >= maxAttempts) {
            return VerificationResult.TOO_MANY_ATTEMPTS;
        }
        if (verification.getExpiresAt().isBefore(Instant.now())) {
            return VerificationResult.EXPIRED;
        }

        boolean matches = constantTimeEquals(hash(submittedCode), verification.getCodeHash());
        if (!matches) {
            verification.setAttempts(verification.getAttempts() + 1);
            repository.save(verification);
            return VerificationResult.INVALID_CODE;
        }

        repository.deleteByUserId(userId); // one-time use
        return VerificationResult.SUCCESS;
    }

    private String hash(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hashBytes = digest.digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hashBytes);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is a required JDK algorithm; this should never happen", e);
        }
    }

    private boolean constantTimeEquals(String a, String b) {
        return MessageDigest.isEqual(a.getBytes(StandardCharsets.UTF_8), b.getBytes(StandardCharsets.UTF_8));
    }
}
