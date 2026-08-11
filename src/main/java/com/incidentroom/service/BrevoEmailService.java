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
 * Production EmailService — sends through Brevo's transactional email HTTP API.
 *
 * Why Brevo instead of Gmail SMTP or the Gmail API:
 *  - Gmail SMTP (see GmailSmtpEmailService, dormant): raw SMTP ports are blocked outbound on
 *    Railway's Free/Trial/Hobby plans regardless of app config.
 *  - Gmail API (see GmailApiEmailService, dormant): works over HTTPS, but gmail.send is a
 *    "sensitive" OAuth scope — moving the OAuth app out of Testing mode requires Google's full
 *    verification process (branding review, domain ownership, possibly a demo video), which is
 *    disproportionate for a small app's verification/reset emails. While the app stays in
 *    Testing mode, refresh tokens expire every 7 days, which isn't viable for production.
 *  - Brevo's free tier sidesteps both problems: it's a plain HTTPS API (POST, so immune to SMTP
 *    port blocking), it lets you send AS a personal Gmail address once that address completes
 *    Brevo's lightweight single-sender verification (click a confirmation link — no domain/DNS
 *    ownership required), and — unlike Resend without a verified domain — it can send to any
 *    recipient, not just the account owner. Free tier cap is 300 emails/day, ample for an MVP.
 *
 * A send failure is deliberately NOT swallowed, matching the other EmailService implementations —
 * it propagates as an unchecked exception, which GlobalExceptionHandler turns into a 500, so a
 * failed registration/resend/forgot-password never lies to the caller with a success response.
 */
@Service
@Profile("prod")
public class BrevoEmailService implements EmailService {

    private static final Logger log = LoggerFactory.getLogger(BrevoEmailService.class);
    private static final URI BREVO_ENDPOINT = URI.create("https://api.brevo.com/v3/smtp/email");

    private final HttpClient httpClient;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final String apiKey;
    private final String fromAddress;
    private final String fromName;

    public BrevoEmailService(
            @Value("${app.email.brevo.api-key}") String apiKey,
            @Value("${app.email.brevo.sender-address}") String fromAddress,
            @Value("${app.email.brevo.sender-name:Incident Room}") String fromName) {
        this.apiKey = apiKey;
        this.fromAddress = fromAddress;
        this.fromName = fromName;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .build();
    }

    @Override
    public void sendVerificationCode(String toEmail, String displayName, String code) {
        send(toEmail, "Your Incident Room verification code",
                renderHtml(displayName, code, "verification code",
                        "This code expires shortly. If you didn't request this, you can safely ignore this email."));
    }

    @Override
    public void sendPasswordResetCode(String toEmail, String displayName, String code) {
        send(toEmail, "Reset your Incident Room password",
                renderHtml(displayName, code, "password reset code",
                        "This code expires in 15 minutes. If you did not request a password reset, please ignore this email."));
    }

    private void send(String toEmail, String subject, String html) {
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalStateException("BREVO_API_KEY is not configured in environment");
        }

        Map<String, Object> body = Map.of(
                "sender", Map.of("name", fromName, "email", fromAddress),
                "to", List.of(Map.of("email", toEmail)),
                "subject", subject,
                "htmlContent", html);

        String payload;
        try {
            payload = objectMapper.writeValueAsString(body);
        } catch (IOException e) {
            // Only possible if the Map above somehow contains something non-serializable — it
            // doesn't, so this is truly "should never happen," but fail loudly rather than send
            // garbage.
            throw new IllegalStateException("Failed to serialize outgoing Brevo email payload", e);
        }

        HttpRequest request = HttpRequest.newBuilder(BREVO_ENDPOINT)
                .timeout(Duration.ofSeconds(10))
                // Brevo uses a plain "api-key" header, not an Authorization: Bearer scheme.
                .header("api-key", apiKey)
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(payload))
                .build();

        try {
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() / 100 != 2) {
                // Never log the code, recipient, or response body alongside a provider error —
                // this ends up in prod logs and Brevo's error body can echo request details back.
                log.error("Brevo rejected an email send: status={}", response.statusCode());
                throw new IllegalStateException(
                        "Brevo rejected the email (status " + response.statusCode() + ")");
            }
        } catch (IOException e) {
            log.error("Failed to reach Brevo while sending an email", e);
            throw new IllegalStateException("Failed to send email via Brevo", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while sending email via Brevo", e);
        }
    }

    private String renderHtml(String displayName, String code, String codeLabel, String footer) {
        return "<p>Hi " + escapeHtml(displayName) + ",</p>"
                + "<p>Your Incident Room " + codeLabel + " is:</p>"
                + "<p style=\"font-size:24px;font-weight:bold;letter-spacing:4px;\">" + escapeHtml(code) + "</p>"
                + "<p>" + footer + "</p>";
    }

    private String escapeHtml(String value) {
        return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }
}