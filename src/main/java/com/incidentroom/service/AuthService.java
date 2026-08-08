package com.incidentroom.service;

import com.incidentroom.common.exception.BadRequestException;
import com.incidentroom.common.exception.ConflictException;
import com.incidentroom.common.exception.EmailNotVerifiedException;
import com.incidentroom.common.exception.InvalidCredentialsException;
import com.incidentroom.domain.AppUser;
import com.incidentroom.domain.Role;
import com.incidentroom.dto.LoginRequest;
import com.incidentroom.dto.RegisterRequest;
import com.incidentroom.dto.ResendVerificationRequest;
import com.incidentroom.dto.UserResponse;
import com.incidentroom.dto.UserStatsDto;
import com.incidentroom.dto.VerifyEmailRequest;
import com.incidentroom.repository.AppUserRepository;
import com.incidentroom.security.CookieService;
import com.incidentroom.security.CustomOAuth2UserService;
import com.incidentroom.security.JwtService;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;

@Service
public class AuthService {

    private static final Logger log = LoggerFactory.getLogger(AuthService.class);

    private final AppUserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final VerificationCodeService verificationCodeService;
    private final EmailService emailService;
    private final JwtService jwtService;
    private final RefreshTokenService refreshTokenService;
    private final CookieService cookieService;
    private final com.incidentroom.repository.AttemptRepository attemptRepository;
    private final com.incidentroom.repository.EmailVerificationRepository emailVerificationRepository;
    private final com.incidentroom.repository.LeaderboardRepository leaderboardRepository;
    private final com.incidentroom.repository.PasswordResetTokenRepository passwordResetTokenRepository;
    private final java.security.SecureRandom random = new java.security.SecureRandom();

    public AuthService(
            AppUserRepository userRepository,
            PasswordEncoder passwordEncoder,
            VerificationCodeService verificationCodeService,
            EmailService emailService,
            JwtService jwtService,
            RefreshTokenService refreshTokenService,
            CookieService cookieService,
            com.incidentroom.repository.AttemptRepository attemptRepository,
            com.incidentroom.repository.EmailVerificationRepository emailVerificationRepository,
            com.incidentroom.repository.LeaderboardRepository leaderboardRepository,
            com.incidentroom.repository.PasswordResetTokenRepository passwordResetTokenRepository) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.verificationCodeService = verificationCodeService;
        this.emailService = emailService;
        this.jwtService = jwtService;
        this.refreshTokenService = refreshTokenService;
        this.cookieService = cookieService;
        this.attemptRepository = attemptRepository;
        this.emailVerificationRepository = emailVerificationRepository;
        this.leaderboardRepository = leaderboardRepository;
        this.passwordResetTokenRepository = passwordResetTokenRepository;
    }


    /**
     * Deliberately returns the same generic outcome whether the email is new or
     * already registered —
     * see backend security architecture doc §2.3: revealing "this email is already
     * taken" from
     * /register is an account-enumeration leak. An existing, already-verified
     * account attempting to
     * re-register just gets a fresh verification email re-sent rather than an
     * error.
     */
    public void register(RegisterRequest request) {
        String normalizedEmail = request.email().trim().toLowerCase();

        AppUser user = userRepository.findByEmail(normalizedEmail).orElse(null);

        if (user != null && user.isEmailVerified()) {
            // Existing, verified account — do NOT create a duplicate, do NOT error.
            // A real product would send a "someone tried to register with your email"
            // notice here;
            // stubbed as a log line until the email templates for that flow exist.
            log.info("Register attempted for already-verified email — no-op response returned to caller");
            return;
        }
        if (user == null) {
            user = AppUser.builder()
                    .email(normalizedEmail)
                    .displayName(request.displayName().trim())
                    .passwordHash(passwordEncoder.encode(request.password()))
                    .emailVerified(false)
                    .role(Role.USER)
                    .createdAt(Instant.now())
                    .updatedAt(Instant.now())
                    .build();
            userRepository.save(user);
        } else {
            // Unverified account re-registering (e.g. they lost the original code) —
            // refresh their
            // password/display name in case they're correcting a typo, then re-send
            // verification.
            user.setPasswordHash(passwordEncoder.encode(request.password()));
            user.setDisplayName(request.displayName().trim());
            user.setUpdatedAt(Instant.now());
            userRepository.save(user);
        }

        sendVerificationCode(user);
    }

    /**
     * Updates the authenticated user's display name.
     * The JWT subject is the user's email — same pattern used by every other
     * authenticated endpoint in this service.
     */
    public UserResponse updateDisplayName(String userIdentifier, String newDisplayName) {
        AppUser user = userRepository.findById(userIdentifier)
                .or(() -> userRepository.findByEmail(userIdentifier))
                .orElseThrow(InvalidCredentialsException::new);
        user.setDisplayName(newDisplayName);
        user.setUpdatedAt(Instant.now());
        userRepository.save(user);
        return UserResponse.from(user);
    }


    public void resendVerification(ResendVerificationRequest request) {
        userRepository.findByEmail(request.email().trim().toLowerCase()).ifPresent(user -> {
            if (!user.isEmailVerified()) {
                sendVerificationCode(user);
            }
            // Silently no-op for already-verified accounts — same anti-enumeration
            // reasoning as register().
        });
    }

    private void sendVerificationCode(AppUser user) {
        String code = verificationCodeService.issueNewCode(user.getId());
        emailService.sendVerificationCode(user.getEmail(), user.getDisplayName(), code);
    }

    public UserResponse verifyEmail(VerifyEmailRequest request, HttpServletRequest httpRequest,
            HttpServletResponse httpResponse) {
        AppUser user = userRepository.findByEmail(request.email().trim().toLowerCase())
                .orElseThrow(() -> new BadRequestException("Invalid or expired verification code."));

        VerificationCodeService.VerificationResult result = verificationCodeService.verify(user.getId(),
                request.code());

        switch (result) {
            case SUCCESS -> {
                user.setEmailVerified(true);
                user.setUpdatedAt(Instant.now());
                userRepository.save(user);
                issueSession(user, httpRequest, httpResponse);
                return UserResponse.from(user);
            }
            case TOO_MANY_ATTEMPTS -> throw new BadRequestException(
                    "Too many incorrect attempts. Request a new code and try again.");
            case EXPIRED -> throw new BadRequestException("This code has expired. Request a new one.");
            case NO_PENDING_CODE ->
                throw new BadRequestException("No verification pending for this email. Request a new code.");
            default -> throw new BadRequestException("Invalid or expired verification code.");
        }
    }

    public UserResponse login(LoginRequest request, HttpServletRequest httpRequest, HttpServletResponse httpResponse) {
        AppUser user = userRepository.findByEmail(request.email().trim().toLowerCase())
                .orElseThrow(InvalidCredentialsException::new);

        if (user.getPasswordHash() == null) {
            // OAuth-only account attempting password login — same generic error, don't
            // reveal
            // "this account uses Google" (still an account-enumeration-adjacent leak).
            throw new InvalidCredentialsException();
        }

        if (!passwordEncoder.matches(request.password(), user.getPasswordHash())) {
            throw new InvalidCredentialsException();
        }

        if (!user.isEmailVerified()) {
            throw new EmailNotVerifiedException("Please verify your email before logging in.");
        }

        issueSession(user, httpRequest, httpResponse);
        return UserResponse.from(user);
    }

    /**
     * Shared by verify-email, login, and the OAuth2 success handler — the one place
     * session cookies get issued.
     */
    public void issueSession(AppUser user, HttpServletRequest httpRequest, HttpServletResponse httpResponse) {
        String accessToken = jwtService.issueAccessToken(user);
        cookieService.setAccessCookie(httpResponse, accessToken, jwtService.getAccessTokenTtlSeconds());

        RefreshTokenService.IssuedToken refreshToken = refreshTokenService.issueNewFamily(
                user.getId(), httpRequest.getHeader("User-Agent"), clientIp(httpRequest));
        cookieService.setRefreshCookie(httpResponse, refreshToken.plaintextToken(),
                (int) java.time.Duration.ofDays(30).toSeconds());
        cookieService.setCsrfCookie(httpResponse);
    }

    public UserResponse refresh(String refreshTokenCookieValue, HttpServletRequest httpRequest,
            HttpServletResponse httpResponse) {
        if (refreshTokenCookieValue == null) {
            throw new InvalidCredentialsException();
        }

        RefreshTokenService.RotationOutcome outcome = refreshTokenService.rotate(
                refreshTokenCookieValue, httpRequest.getHeader("User-Agent"), clientIp(httpRequest));

        if (outcome.result() != RefreshTokenService.RotationResult.SUCCESS) {
            cookieService.clearAuthCookies(httpResponse);
            throw new InvalidCredentialsException();
        }

        AppUser user = userRepository.findById(outcome.userId())
                .orElseThrow(InvalidCredentialsException::new);

        String accessToken = jwtService.issueAccessToken(user);
        cookieService.setAccessCookie(httpResponse, accessToken, jwtService.getAccessTokenTtlSeconds());
        cookieService.setRefreshCookie(httpResponse, outcome.newToken().plaintextToken(),
                (int) java.time.Duration.ofDays(30).toSeconds());
        cookieService.setCsrfCookie(httpResponse);

        return UserResponse.from(user);
    }

    public void logout(String refreshTokenCookieValue, HttpServletResponse httpResponse) {
        if (refreshTokenCookieValue != null) {
            refreshTokenService.revokeSingle(refreshTokenCookieValue);
        }
        cookieService.clearAuthCookies(httpResponse);
    }

    public void logoutAllSessions(String userId, HttpServletResponse httpResponse) {
        refreshTokenService.revokeAllForUser(userId);
        cookieService.clearAuthCookies(httpResponse);
    }

    private String clientIp(HttpServletRequest request) {
        return request.getRemoteAddr();
    }

    /**
     * Exposed for CustomOAuth2UserService, which needs find-or-create/link logic
     * outside the password flow.
     */
    public AppUserRepository userRepository() {
        return userRepository;
    }

    public void deleteAccount(String userId, HttpServletResponse httpResponse) {
        refreshTokenService.revokeAllForUser(userId);
        attemptRepository.deleteByUserId(userId);
        emailVerificationRepository.deleteByUserId(userId);
        leaderboardRepository.deleteByUserId(userId);
        userRepository.deleteById(userId);
        cookieService.clearAuthCookies(httpResponse);
    }

    public com.incidentroom.dto.UserExportDataDto exportUserData(String userId, AttemptService attemptService) {
        AppUser user = userRepository.findById(userId)
                .orElseThrow(() -> new com.incidentroom.common.exception.NotFoundException("User not found"));

        UserResponse profile = UserResponse.from(user);
        UserStatsDto stats = attemptService.getStatsForUser(userId);
        List<com.incidentroom.domain.Attempt> attempts = attemptRepository.findByUserId(userId);
        List<com.incidentroom.domain.LeaderboardEntry> leaderboardEntries = leaderboardRepository.findByUserId(userId);

        return new com.incidentroom.dto.UserExportDataDto(
                profile,
                user.getCreatedAt(),
                user.getUpdatedAt(),
                user.getLastPlayedDate(),
                stats,
                attempts,
                leaderboardEntries,
                Instant.now()
        );
    }

    public void forgotPassword(com.incidentroom.dto.ForgotPasswordRequest request) {
        String email = request.getEmail().trim().toLowerCase();
        userRepository.findByEmail(email).ifPresent(user -> {
            // Generates 6-digit code
            String code = String.format("%06d", random.nextInt(1_000_000));
            String tokenHash = hashToken(code);

            passwordResetTokenRepository.deleteByUserId(user.getId());

            com.incidentroom.domain.PasswordResetToken resetToken = com.incidentroom.domain.PasswordResetToken.builder()
                    .userId(user.getId())
                    .tokenHash(tokenHash)
                    .expiresAt(Instant.now().plus(java.time.Duration.ofMinutes(15)))
                    .createdAt(Instant.now())
                    .attempts(0)
                    .build();

            passwordResetTokenRepository.save(resetToken);
            emailService.sendPasswordResetCode(user.getEmail(), user.getDisplayName(), code);
        });
    }

    public void resetPassword(com.incidentroom.dto.ResetPasswordRequest request) {
        String email = request.getEmail().trim().toLowerCase();
        AppUser user = userRepository.findByEmail(email)
                .orElseThrow(() -> new InvalidCredentialsException("Invalid or expired password reset token"));

        com.incidentroom.domain.PasswordResetToken token = passwordResetTokenRepository.findByUserId(user.getId())
                .orElseThrow(() -> new InvalidCredentialsException("Invalid or expired password reset token"));

        if (token.getAttempts() >= 5) {
            passwordResetTokenRepository.deleteByUserId(user.getId());
            throw new InvalidCredentialsException("Too many invalid attempts. Please request a new password reset code.");
        }

        if (token.getExpiresAt().isBefore(Instant.now())) {
            passwordResetTokenRepository.deleteByUserId(user.getId());
            throw new InvalidCredentialsException("Invalid or expired password reset token");
        }

        boolean matches = constantTimeEquals(hashToken(request.getToken().trim()), token.getTokenHash());
        if (!matches) {
            token.setAttempts(token.getAttempts() + 1);
            passwordResetTokenRepository.save(token);
            throw new InvalidCredentialsException("Invalid or expired password reset token");
        }

        // Token valid — update password and invalidate token & sessions
        user.setPasswordHash(passwordEncoder.encode(request.getNewPassword()));
        userRepository.save(user);
        passwordResetTokenRepository.deleteByUserId(user.getId());
        refreshTokenService.revokeAllForUser(user.getId());
    }

    private String hashToken(String value) {
        try {
            java.security.MessageDigest digest = java.security.MessageDigest.getInstance("SHA-256");
            byte[] hashBytes = digest.digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(hashBytes);
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 algorithm missing", e);
        }
    }

    private boolean constantTimeEquals(String a, String b) {
        return java.security.MessageDigest.isEqual(
                a.getBytes(java.nio.charset.StandardCharsets.UTF_8),
                b.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    public void ensureNotConflictingProvider(AppUser existing, String provider) {
        if (existing.getOauthProvider() != null && !existing.getOauthProvider().equals(provider)) {
            throw new ConflictException("This email is already linked to a different sign-in method.");
        }
    }
}

