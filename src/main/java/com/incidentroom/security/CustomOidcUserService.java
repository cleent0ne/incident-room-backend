package com.incidentroom.security;

import com.incidentroom.domain.AppUser;

import org.springframework.security.oauth2.client.oidc.userinfo.OidcUserRequest;
import org.springframework.security.oauth2.client.oidc.userinfo.OidcUserService;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Handles the userinfo lookup for any OAuth2 client registration whose scopes include "openid"
 * — currently just Google (see OAuth2ClientRegistrationConfig: {@code .scope("openid", "email",
 * "profile")}). Spring Security decides which userinfo path to use per-registration based purely
 * on the presence of that scope: with it, the framework routes to whatever is registered via
 * {@code .oidcUserService(...)} in SecurityConfig; without it (GitHub's registration has no
 * "openid" scope), it routes to {@code .userService(...)} — i.e. CustomOAuth2UserService.
 *
 * Before this class existed, nothing was registered for the OIDC path, so Spring Security fell
 * back to its own default OidcUserService for Google logins — which knows nothing about our
 * AppUser find-or-create/link logic and never runs CustomOAuth2UserService.loadUser(). The
 * resulting principal had no "appUserId" attribute, so OAuth2AuthenticationSuccessHandler's
 * findById(null) blew up with "IllegalArgumentException: The given id must not be null" —
 * visible to the browser as a bare Whitelabel 500 on the /login/oauth2/code/google callback,
 * since server.error.include-message/include-stacktrace are both off. GitHub was never affected
 * because it never goes through this class.
 *
 * Delegates the actual find-or-create logic to CustomOAuth2UserService.resolveAppUser so both
 * flows share one implementation, then wraps the result in AppOidcUser to carry the appUserId
 * attribute forward (DefaultOidcUser has no constructor for arbitrary extra attributes).
 */
@Service
public class CustomOidcUserService extends OidcUserService {

    private final CustomOAuth2UserService customOAuth2UserService;

    public CustomOidcUserService(CustomOAuth2UserService customOAuth2UserService) {
        this.customOAuth2UserService = customOAuth2UserService;
    }

    @Override
    public OidcUser loadUser(OidcUserRequest userRequest) throws OAuth2AuthenticationException {
        OidcUser oidcUser = super.loadUser(userRequest);
        String provider = userRequest.getClientRegistration().getRegistrationId();

        AppUser appUser = customOAuth2UserService.resolveAppUser(provider, oidcUser.getAttributes());

        return new AppOidcUser(
                oidcUser,
                List.of(() -> "ROLE_" + appUser.getRole().name()),
                appUser.getId());
    }
}
