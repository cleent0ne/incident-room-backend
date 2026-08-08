package com.incidentroom.service;

/**
 * Abstraction over "how does an email actually get delivered" so the auth flow itself
 * never depends on a specific provider. Swap the implementation (see ConsoleEmailService for the
 * local-dev stub) for a real transactional provider (Resend/SES/Postmark) via a @Profile-scoped bean
 * without touching AuthService at all.
 */
public interface EmailService {

    void sendVerificationCode(String toEmail, String displayName, String code);

    void sendPasswordResetCode(String toEmail, String displayName, String code);
}

