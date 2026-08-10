package com.incidentroom.service;

import com.fasterxml.jackson.databind.ObjectMapper;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * Alternative production EmailService — sends the verification code through Resend's HTTP API
 * directly (a plain java.net.http call rather than pulling in Resend's Java SDK, since the whole
 * integration is one POST with a JSON body; not worth a new dependency for that).
 *
 * Currently dormant: GmailSmtpEmailService is the active "prod" implementation (Gmail SMTP,
 * chosen for zero-domain-required MVP use). Only one EmailService bean can be active per profile,
 * so this is registered under its own "resend" profile instead — activate it (e.g.
 * `SPRING_PROFILES_ACTIVE=prod,resend` and set RESEND_API_KEY) if/when Gmail's ~500/day cap or
 * spam-landing risk becomes a real constraint and you want a dedicated transactional-email
 * provider instead.
 *
 * A send failure (network error, non-2xx from Resend) is deliberately NOT swallowed — it propagates
 * as an unchecked exception, which GlobalExceptionHandler turns into a 500. Registration/resend
 * genuinely didn't succeed if the email didn't go out, so the caller should see a failure and be
 * able to retry, rather than a 202 that lied about it.
 */
@Service
@Profile("resend")
public class ResendEmailService implements EmailService {

    private static final Logger log = LoggerFactory.getLogger(ResendEmailService.class);
    private static final URI RESEND_ENDPOINT = URI.create("https://api.resend.com/emails");

    private final HttpClient httpClient;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final String apiKey;
    private final String fromAddress;

    public ResendEmailService(
            @Value("${app.email.resend-api-key}") String apiKey,
            @Value("${app.email.from-address}") String fromAddress) {
        this.apiKey = apiKey;
        this.fromAddress = fromAddress;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .build();
    }

    @Override
    public void sendVerificationCode(String toEmail, String displayName, String code) {
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalStateException("RESEND_API_KEY is not configured in environment");
        }

        Map<String, Object> body = Map.of(
                "from", fromAddress,
                "to", List.of(toEmail),
                "subject", "Your Incident Room verification code",
                "html", renderHtml(displayName, code));

        String payload;
        try {
            payload = objectMapper.writeValueAsString(body);
        } catch (IOException e) {
            // Only possible if the Map above somehow contains something non-serializable — it doesn't,
            // so this is truly "should never happen," but fail loudly rather than send garbage.
            throw new IllegalStateException("Failed to serialize verification email payload", e);
        }

        HttpRequest request = HttpRequest.newBuilder(RESEND_ENDPOINT)
                .timeout(Duration.ofSeconds(10))
                .header("Authorization", "Bearer " + apiKey)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(payload))
                .build();

        try {
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() / 100 != 2) {
                // Never log the code or the recipient's full address alongside a provider error —
                // this ends up in prod logs, and the code is a live credential until it expires.
                log.error("Resend rejected a verification email: status={}", response.statusCode());
                throw new IllegalStateException(
                        "Email provider rejected the verification email (status " + response.statusCode() + ")");
            }
        } catch (IOException e) {
            log.error("Failed to reach Resend while sending a verification email", e);
            throw new IllegalStateException("Failed to send verification email", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while sending verification email", e);
        }
    }

    @Override
    public void sendPasswordResetCode(String toEmail, String displayName, String code) {
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalStateException("RESEND_API_KEY is not configured in environment");
        }

        Map<String, Object> body = Map.of(
                "from", fromAddress,
                "to", List.of(toEmail),
                "subject", "Reset your Incident Room password",
                "html", renderResetHtml(displayName, code));

        String payload;
        try {
            payload = objectMapper.writeValueAsString(body);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to serialize password reset email payload", e);
        }

        HttpRequest request = HttpRequest.newBuilder(RESEND_ENDPOINT)
                .timeout(Duration.ofSeconds(10))
                .header("Authorization", "Bearer " + apiKey)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(payload))
                .build();

        try {
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() / 100 != 2) {
                log.error("Resend rejected a password reset email: status={}", response.statusCode());
                throw new IllegalStateException(
                        "Email provider rejected the password reset email (status " + response.statusCode() + ")");
            }
        } catch (IOException e) {
            log.error("Failed to reach Resend while sending a password reset email", e);
            throw new IllegalStateException("Failed to send password reset email", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while sending password reset email", e);
        }
    }

    private String renderHtml(String displayName, String code) {
        return "<p>Hi " + escapeHtml(displayName) + ",</p>"
                + "<p>Your Incident Room verification code is:</p>"
                + "<p style=\"font-size:24px;font-weight:bold;letter-spacing:4px;\">" + escapeHtml(code) + "</p>"
                + "<p>This code expires shortly. If you didn't request this, you can safely ignore this email.</p>";
    }

    private String renderResetHtml(String displayName, String code) {
        return "<p>Hi " + escapeHtml(displayName) + ",</p>"
                + "<p>Your Incident Room password reset code is:</p>"
                + "<p style=\"font-size:24px;font-weight:bold;letter-spacing:4px;\">" + escapeHtml(code) + "</p>"
                + "<p>This code expires in 15 minutes. If you did not request a password reset, please ignore this email.</p>";
    }

    private String escapeHtml(String value) {
        return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }
}