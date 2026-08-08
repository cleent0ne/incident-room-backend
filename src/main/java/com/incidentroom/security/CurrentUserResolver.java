package com.incidentroom.security;

import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Component;

/**
 * For endpoints under `anyRequest().authenticated()`, Spring Security itself already rejects
 * unauthenticated requests before a controller is ever reached, which is why AuthController can
 * get away with a naive `authentication.isAuthenticated()` check.
 *
 * For permitAll endpoints that still want to know "is there a logged-in user, or not" — like the
 * attempt endpoints, which support both anonymous and authenticated play — that shortcut doesn't
 * work: Spring's default AnonymousAuthenticationFilter still populates the SecurityContext with an
 * AnonymousAuthenticationToken (principal name "anonymousUser") whenever there's no real
 * authentication, and that token's own isAuthenticated() returns true. Treating its name as a real
 * user id would silently corrupt the anonymous-play capability-token pattern (every anonymous
 * attempt would end up with userId="anonymousUser" instead of null). This is the one place that
 * distinction is made, so every future permitAll-but-auth-aware endpoint should use this rather
 * than re-deriving the check.
 */
@Component
public class CurrentUserResolver {

    public String resolve(Authentication authentication) {
        if (authentication == null || authentication instanceof AnonymousAuthenticationToken) {
            return null;
        }
        return authentication.getName();
    }
}
