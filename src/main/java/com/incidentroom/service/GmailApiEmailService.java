package com.incidentroom.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.Map;

/**
 * Alternative production EmailService — sends Gmail mail over HTTPS via the Gmail API, instead of
 * raw SMTP (see GmailSmtpEmailService, also dormant, under the "gmail-smtp" profile).
 *
 * Currently dormant: BrevoEmailService is the active "prod" implementation. This class was the
 * first fix attempted for Railway blocking outbound SMTP (Gmail API is HTTPS, port 443, never
 * blocked) — but gmail.send is a "sensitive" OAuth scope, so keeping this active in real
 * production requires either (a) completing Google's full OAuth app verification (branding
 * review, domain ownership, possibly a demo video — disproportionate for this app's needs), or
 * (b) staying in Testing mode, where refresh tokens issued expire every 7 days. Neither was worth
 * it once Brevo turned out to need none of that. Left in the codebase in case Google's
 * verification is ever worth completing (e.g. if Gmail's sending reputation specifically matters).
 *
 * Auth flow: reuses the existing Google OAuth client (GOOGLE_OAUTH_CLIENT_ID /
 * GOOGLE_OAUTH_CLIENT_SECRET, already present for user login) plus a separate long-lived
 * GMAIL_REFRESH_TOKEN issued out-of-band by granting the gmail.send scope to the sending account
 * — see the setup steps used previously (Google OAuth Playground) for how that was generated.
 *
 * Each send exchanges the refresh token for a short-lived access token (not cached here — one
 * extra token-endpoint round trip per email is a deliberate simplicity trade-off given this app's
 * low email volume) and then POSTs a base64url-encoded RFC 2822 MIME message to Gmail's
 * messages.send endpoint.
 */
@Service
@Profile("gmail-api")
public class GmailApiEmailService implements EmailService {

    private static final Logger log = LoggerFactory.getLogger(GmailApiEmailService.class);
    private static final URI TOKEN_ENDPOINT = URI.create("https://oauth2.googleapis.com/token");
    private static final URI SEND_ENDPOINT =
            URI.create("https://www.googleapis.com/gmail/v1/users/me/messages/send");

    private final HttpClient httpClient;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final String clientId;
    private final String clientSecret;
    private final String refreshToken;
    private final String fromAddress;

    public GmailApiEmailService(
            @Value("${app.email.gmail-api.client-id}") String clientId,
            @Value("${app.email.gmail-api.client-secret}") String clientSecret,
            @Value("${app.email.gmail-api.refresh-token}") String refreshToken,
            @Value("${app.email.gmail-api.sender-address}") String fromAddress) {
        this.clientId = clientId;
        this.clientSecret = clientSecret;
        this.refreshToken = refreshToken;
        this.fromAddress = fromAddress;
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
        String accessToken = fetchAccessToken();
        String raw = buildRawMessage(toEmail, subject, html);

        String payload;
        try {
            payload = objectMapper.writeValueAsString(Map.of("raw", raw));
        } catch (IOException e) {
            // Only possible if the Map above somehow contains something non-serializable — it
            // doesn't, so this is truly "should never happen," but fail loudly rather than send
            // garbage.
            throw new IllegalStateException("Failed to serialize outgoing Gmail API payload", e);
        }

        HttpRequest request = HttpRequest.newBuilder(SEND_ENDPOINT)
                .timeout(Duration.ofSeconds(10))
                .header("Authorization", "Bearer " + accessToken)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(payload))
                .build();

        try {
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() / 100 != 2) {
                // Never log the code, recipient, or response body alongside a provider error —
                // this ends up in prod logs and Gmail's error body can echo request details back.
                log.error("Gmail API rejected an email send: status={}", response.statusCode());
                throw new IllegalStateException(
                        "Gmail API rejected the email (status " + response.statusCode() + ")");
            }
        } catch (IOException e) {
            log.error("Failed to reach the Gmail API while sending an email", e);
            throw new IllegalStateException("Failed to send email via Gmail API", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while sending email via Gmail API", e);
        }
    }

    /**
     * Exchanges the long-lived refresh token for a short-lived (~1hr) OAuth access token.
     * Google's token endpoint is plain form-encoded, not JSON, on the request side.
     */
    private String fetchAccessToken() {
        String form = "client_id=" + urlEncode(clientId)
                + "&client_secret=" + urlEncode(clientSecret)
                + "&refresh_token=" + urlEncode(refreshToken)
                + "&grant_type=refresh_token";

        HttpRequest request = HttpRequest.newBuilder(TOKEN_ENDPOINT)
                .timeout(Duration.ofSeconds(10))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(form))
                .build();

        try {
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() / 100 != 2) {
                // A stale/revoked refresh token or bad client credentials both land here — treat
                // both as a hard failure rather than trying to distinguish them, since neither is
                // recoverable without a human regenerating the token.
                log.error("Google token endpoint rejected the refresh token exchange: status={}",
                        response.statusCode());
                throw new IllegalStateException(
                        "Failed to refresh Gmail API access token (status " + response.statusCode() + ")");
            }
            JsonNode node = objectMapper.readTree(response.body());
            JsonNode accessToken = node.get("access_token");
            if (accessToken == null || accessToken.asText().isBlank()) {
                throw new IllegalStateException("Google token endpoint response had no access_token");
            }
            return accessToken.asText();
        } catch (IOException e) {
            log.error("Failed to reach Google's token endpoint", e);
            throw new IllegalStateException("Failed to refresh Gmail API access token", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while refreshing Gmail API access token", e);
        }
    }

    /**
     * Builds a minimal RFC 2822 message and base64url-encodes it the way the Gmail API's "raw"
     * field requires (URL-safe alphabet, no padding — this is NOT standard base64).
     */
    private String buildRawMessage(String toEmail, String subject, String html) {
        String message = "From: Incident Room <" + fromAddress + ">\r\n"
                + "To: " + toEmail + "\r\n"
                + "Subject: " + encodeSubject(subject) + "\r\n"
                + "MIME-Version: 1.0\r\n"
                + "Content-Type: text/html; charset=UTF-8\r\n"
                + "\r\n"
                + html;
        byte[] bytes = message.getBytes(StandardCharsets.UTF_8);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    /** RFC 2047 encoded-word so a UTF-8 subject can't corrupt the raw MIME headers. */
    private String encodeSubject(String subject) {
        byte[] bytes = subject.getBytes(StandardCharsets.UTF_8);
        return "=?UTF-8?B?" + Base64.getEncoder().encodeToString(bytes) + "?=";
    }

    private String urlEncode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
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