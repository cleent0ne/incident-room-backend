package com.incidentroom.security;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.security.SecureRandom;
import java.util.Base64;

/**
 * Owns the three cookies auth relies on:
 *  - ir_access  (HttpOnly)  — short-lived JWT, sent on every request
 *  - ir_refresh (HttpOnly)  — opaque refresh token, scoped to /api/v1/auth/refresh only
 *  - ir_csrf    (NOT HttpOnly) — double-submit CSRF token, must be readable by frontend JS
 *
 * See the backend security architecture doc, section 1.2, for the reasoning behind each flag.
 */
@Service
public class CookieService {

    public static final String ACCESS_COOKIE = "ir_access";
    public static final String REFRESH_COOKIE = "ir_refresh";
    public static final String CSRF_COOKIE = "ir_csrf";

    private final boolean secure;
    private final String domain;

    public CookieService(
            @Value("${app.cookie.secure}") boolean secure,
            @Value("${app.cookie.domain}") String domain) {
        this.secure = secure;
        this.domain = domain;
    }

    public void setAccessCookie(HttpServletResponse response, String token, int maxAgeSeconds) {
        addCookie(response, ACCESS_COOKIE, token, "/", maxAgeSeconds, true);
    }

    public void setRefreshCookie(HttpServletResponse response, String token, int maxAgeSeconds) {
        addCookie(response, REFRESH_COOKIE, token, "/api/v1/auth", maxAgeSeconds, true);
    }

    public void setCsrfCookie(HttpServletResponse response) {
        String csrfToken = generateOpaqueToken();
        addCookie(response, CSRF_COOKIE, csrfToken, "/", 60 * 60 * 24 * 30, false);
    }

    /** Clears all three cookies — used on logout. */
    public void clearAuthCookies(HttpServletResponse response) {
        addCookie(response, ACCESS_COOKIE, "", "/", 0, true);
        addCookie(response, REFRESH_COOKIE, "", "/api/v1/auth", 0, true);
        addCookie(response, CSRF_COOKIE, "", "/", 0, false);
    }

    public String generateOpaqueToken() {
        byte[] bytes = new byte[32];
        new SecureRandom().nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private void addCookie(HttpServletResponse response, String name, String value, String path,
                            int maxAgeSeconds, boolean httpOnly) {
        Cookie cookie = new Cookie(name, value);
        cookie.setHttpOnly(httpOnly);
        cookie.setSecure(secure);
        cookie.setPath(path);
        cookie.setMaxAge(maxAgeSeconds);
        if (secure) {
            // Only scope to a specific domain once actually running behind HTTPS on a real domain —
            // on localhost, setting Domain breaks cookie delivery entirely.
            cookie.setDomain(domain);
        }
        cookie.setAttribute("SameSite", "Strict");
        response.addCookie(cookie);
    }
}
