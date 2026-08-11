package com.incidentroom.service;

import jakarta.mail.MessagingException;
import jakarta.mail.internet.MimeMessage;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.mail.MailException;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Service;

import java.io.UnsupportedEncodingException;

/**
 * Alternative production EmailService — sends through Gmail's SMTP relay via Spring's
 * auto-configured JavaMailSender (see spring.mail.* in application-prod.yml, and
 * spring-boot-starter-mail in pom.xml).
 *
 * Currently dormant: GmailApiEmailService is the active "prod" implementation, because this
 * relies on raw SMTP (port 587/465), which many PaaS hosts — including Railway's Free/Trial/Hobby
 * plans — block outbound at the network level regardless of app config. If you deploy somewhere
 * that does allow outbound SMTP (a VM/VPS, or a paid Railway plan), this remains a simpler option
 * than the Gmail API's OAuth dance — activate it with `SPRING_PROFILES_ACTIVE=prod,gmail-smtp`
 * (and remove/rename the "prod" profile on GmailApiEmailService, since only one EmailService bean
 * may be active at a time) plus GMAIL_USERNAME / GMAIL_APP_PASSWORD.
 *
 * Requires a Gmail account with 2-Step Verification turned on and an App Password generated for
 * it (Google's SMTP relay rejects a normal account password). Gmail's relay also requires the
 * "from" address to match the authenticated account, so — unlike ResendEmailService, which took
 * an independently configurable from-address — this always sends as spring.mail.username.
 */
@Service
@Profile("gmail-smtp")
public class GmailSmtpEmailService implements EmailService {

    private static final Logger log = LoggerFactory.getLogger(GmailSmtpEmailService.class);

    private final JavaMailSender mailSender;
    private final String fromAddress;

    public GmailSmtpEmailService(
            JavaMailSender mailSender,
            @Value("${spring.mail.username}") String fromAddress) {
        this.mailSender = mailSender;
        this.fromAddress = fromAddress;
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
        try {
            MimeMessage message = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(message, false, "UTF-8");
            helper.setFrom(fromAddress, "Incident Room");
            helper.setTo(toEmail);
            helper.setSubject(subject);
            helper.setText(html, true);
            mailSender.send(message);
        } catch (MailException e) {
            // Same "don't swallow" reasoning as ResendEmailService: a send failure here means
            // registration/resend/forgot-password genuinely didn't succeed, so it should surface
            // as a 500 (via GlobalExceptionHandler) rather than a 202/200 that lied about it.
            log.error("Gmail SMTP rejected an email send: {}", e.getMessage());
            throw new IllegalStateException("Failed to send email via Gmail SMTP", e);
        } catch (MessagingException | UnsupportedEncodingException e) {
            log.error("Failed to construct outgoing email", e);
            throw new IllegalStateException("Failed to construct outgoing email", e);
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