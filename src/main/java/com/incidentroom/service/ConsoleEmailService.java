package com.incidentroom.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

/**
 * Local-dev only — "sends" the verification code by logging it, so the full register → verify →
 * login flow can be exercised end to end without wiring up a real transactional email provider.
 * Never active outside the "local" profile; production wiring is a separate implementation
 * (e.g. ResendEmailService) registered instead once a real provider account exists.
 */
@Service
@Profile("local")
public class ConsoleEmailService implements EmailService {

    private static final Logger log = LoggerFactory.getLogger(ConsoleEmailService.class);

    @Override
    public void sendVerificationCode(String toEmail, String displayName, String code) {
        log.info("[DEV EMAIL] Verification code for {} ({}): {}", toEmail, displayName, code);
    }

    @Override
    public void sendPasswordResetCode(String toEmail, String displayName, String code) {
        log.info("[DEV EMAIL] Password reset code for {} ({}): {}", toEmail, displayName, code);
    }
}

