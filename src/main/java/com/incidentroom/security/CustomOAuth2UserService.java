package com.incidentroom.security;

import com.incidentroom.domain.AppUser;
import com.incidentroom.domain.Role;
import com.incidentroom.service.AuthService;

import org.springframework.security.oauth2.client.userinfo.DefaultOAuth2UserService;
import org.springframework.security.oauth2.client.userinfo.OAuth2UserRequest;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.user.DefaultOAuth2User;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Runs after the OAuth provider (Google/GitHub) authenticates the user and hands back their
 * profile. Responsible for: finding an existing linked account, LINKING to an existing
 * email/password account with the same verified email (not creating a duplicate — see
 * backend security architecture doc §3.1), or creating a brand new account with
 * emailVerified=true immediately, since the provider has already verified the email itself.
 */
@Service
public class CustomOAuth2UserService extends DefaultOAuth2UserService {

    public static final String APP_USER_ID_ATTRIBUTE = "appUserId";

    private final AuthService authService;

    public CustomOAuth2UserService(AuthService authService) {
        this.authService = authService;
    }

    @Override
    public OAuth2User loadUser(OAuth2UserRequest userRequest) throws OAuth2AuthenticationException {
        OAuth2User oauth2User = super.loadUser(userRequest);
        String provider = userRequest.getClientRegistration().getRegistrationId(); // "google" | "github"

        AppUser appUser = resolveAppUser(provider, oauth2User.getAttributes());

        Map<String, Object> attributes = new HashMap<>(oauth2User.getAttributes());
        attributes.put(APP_USER_ID_ATTRIBUTE, appUser.getId());

        return new DefaultOAuth2User(List.of(() -> "ROLE_" + appUser.getRole().name()), attributes, "email");
    }

    /**
     * Shared by this class (plain OAuth2 flow — GitHub, which has no "openid" scope) and
     * {@link CustomOidcUserService} (OIDC flow — Google, whose "openid" scope makes Spring
     * Security route userinfo lookups to the OIDC path instead of this class's loadUser(); see
     * CustomOidcUserService's javadoc for the full explanation). Both need the exact same
     * find-or-create/link logic, so it lives here once rather than being duplicated.
     */
    AppUser resolveAppUser(String provider, Map<String, Object> attributes) {
        ProviderProfile profile = extractProfile(provider, attributes);
        return findOrCreateUser(provider, profile);
    }

    private AppUser findOrCreateUser(String provider, ProviderProfile profile) {
        if (!profile.emailVerified()) {
            throw new OAuth2AuthenticationException("OAuth account email is not verified by " + provider + ".");
        }

        Optional<AppUser> byProviderIdentity = authService.userRepository()
                .findByOauthProviderAndOauthProviderId(provider, profile.providerId());
        if (byProviderIdentity.isPresent()) {
            return byProviderIdentity.get();
        }

        Optional<AppUser> byEmail = authService.userRepository().findByEmail(profile.email());
        if (byEmail.isPresent()) {
            // Link to the existing account rather than creating a duplicate — the critical branch
            // most half-built OAuth integrations get wrong.
            AppUser existing = byEmail.get();
            authService.ensureNotConflictingProvider(existing, provider);
            existing.setOauthProvider(provider);
            existing.setOauthProviderId(profile.providerId());
            existing.setEmailVerified(true); // provider-verified email supersedes our own verification requirement
            existing.setUpdatedAt(Instant.now());
            return authService.userRepository().save(existing);
        }

        AppUser created = AppUser.builder()
                .email(profile.email())
                .displayName(profile.displayName())
                .emailVerified(true)
                .oauthProvider(provider)
                .oauthProviderId(profile.providerId())
                .role(Role.USER)
                .createdAt(Instant.now())
                .updatedAt(Instant.now())
                .build();
        return authService.userRepository().save(created);
    }

    private record ProviderProfile(String providerId, String email, String displayName, boolean emailVerified) {}

    @SuppressWarnings("unchecked")
    private ProviderProfile extractProfile(String provider, Map<String, Object> attributes) {
        return switch (provider) {
            case "google" -> new ProviderProfile(
                    String.valueOf(attributes.get("sub")),
                    String.valueOf(attributes.get("email")),
                    String.valueOf(attributes.getOrDefault("name", attributes.get("email"))),
                    Boolean.parseBoolean(String.valueOf(attributes.getOrDefault("email_verified", "false"))));
            case "github" -> new ProviderProfile(
                    String.valueOf(attributes.get("id")),
                    resolveGithubEmail(attributes),
                    String.valueOf(attributes.getOrDefault("name", attributes.get("login"))),
                    // GitHub only attaches primary verified emails when user:email scope is granted
                    // or email attribute is non-null. Default to true if email resolved, or check explicit attribute if present.
                    Boolean.parseBoolean(String.valueOf(attributes.getOrDefault("email_verified", "true"))));
            default -> throw new OAuth2AuthenticationException("Unsupported OAuth provider: " + provider);
        };
    }

    private String resolveGithubEmail(Map<String, Object> attributes) {
        Object email = attributes.get("email");
        if (email != null) {
            return String.valueOf(email);
        }
        // GitHub omits email from the primary profile response if the user has it set to private;
        // the "user:email" scope is requested specifically so this fallback is rarely hit, but if it
        // is, failing loudly is correct — silently fabricating an email would be worse.
        throw new OAuth2AuthenticationException(
                "GitHub account has no public email available. Please make an email public on GitHub or use a different sign-in method.");
    }
}
