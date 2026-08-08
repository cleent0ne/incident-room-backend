package com.incidentroom.security;

import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.core.oidc.OidcIdToken;
import org.springframework.security.oauth2.core.oidc.OidcUserInfo;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;

import java.util.Collection;
import java.util.HashMap;
import java.util.Map;

/**
 * Thin decorator around the OidcUser Spring Security builds from Google's ID token + userinfo
 * claims, so CustomOidcUserService can attach our own "appUserId" attribute onto it. This exists
 * because {@code DefaultOidcUser} has no constructor that accepts an arbitrary extra attribute —
 * its attribute map is always exactly the merged ID token + UserInfo claims, nothing more. See
 * CustomOidcUserService's javadoc for why this attribute needs to exist at all.
 */
final class AppOidcUser implements OidcUser {

    private final OidcUser delegate;
    private final Collection<? extends GrantedAuthority> authorities;
    private final Map<String, Object> attributes;

    AppOidcUser(OidcUser delegate, Collection<? extends GrantedAuthority> authorities, String appUserId) {
        this.delegate = delegate;
        this.authorities = authorities;
        Map<String, Object> merged = new HashMap<>(delegate.getAttributes());
        merged.put(CustomOAuth2UserService.APP_USER_ID_ATTRIBUTE, appUserId);
        this.attributes = merged;
    }

    @Override
    public Map<String, Object> getClaims() {
        return delegate.getClaims();
    }

    @Override
    public OidcUserInfo getUserInfo() {
        return delegate.getUserInfo();
    }

    @Override
    public OidcIdToken getIdToken() {
        return delegate.getIdToken();
    }

    @Override
    public Map<String, Object> getAttributes() {
        return attributes;
    }

    @Override
    public Collection<? extends GrantedAuthority> getAuthorities() {
        return authorities;
    }

    @Override
    public String getName() {
        return delegate.getName();
    }
}
