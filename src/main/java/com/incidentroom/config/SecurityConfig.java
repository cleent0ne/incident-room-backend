package com.incidentroom.config;

import com.incidentroom.security.AdminNotFoundAccessDeniedHandler;
import com.incidentroom.security.AdminNotFoundEntryPoint;
import com.incidentroom.security.ApiAccessDeniedHandler;
import com.incidentroom.security.ApiAuthenticationEntryPoint;
import com.incidentroom.security.CookieService;
import com.incidentroom.security.CustomOAuth2UserService;
import com.incidentroom.security.CustomOidcUserService;
import com.incidentroom.security.JwtAuthenticationFilter;
import com.incidentroom.security.OAuth2AuthenticationFailureHandler;
import com.incidentroom.security.OAuth2AuthenticationSuccessHandler;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.client.registration.InMemoryClientRegistrationRepository;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.List;

@Configuration
@EnableWebSecurity
public class SecurityConfig {

    private static final Logger log = LoggerFactory.getLogger(SecurityConfig.class);

    @Value("${app.frontend-base-url}")
    private String frontendBaseUrl;

    /**
     * BCrypt with strength 12 — a deliberate choice above the library default (10) given how cheap
     * extra hashing cost is at this traffic volume versus the value of slowing down offline
     * cracking attempts if the database were ever exfiltrated.
     */
    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder(12);
    }

    /**
     * Chain #1 — everything under /api/v1/admin/**. Evaluated first (@Order(1)); Spring Security
     * tries filter chains in order and uses the first one whose securityMatcher matches the request.
     * Both "not authenticated" and "authenticated but not ADMIN" resolve to an identical 404 —
     * see AdminNotFoundEntryPoint / AdminNotFoundAccessDeniedHandler for the full rationale.
     */
    @Bean
    @Order(1)
    public SecurityFilterChain adminFilterChain(
            HttpSecurity http,
            JwtAuthenticationFilter jwtAuthenticationFilter,
            AdminNotFoundEntryPoint entryPoint,
            AdminNotFoundAccessDeniedHandler accessDeniedHandler) throws Exception {

        http.securityMatcher("/api/v1/admin/**")
                .sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .csrf(AbstractHttpConfigurer::disable) // admin authoring endpoints are called by trusted tooling/CLI, not the browser session
                .cors(cors -> cors.configurationSource(corsConfigurationSource()))
                .headers(headers -> headers
                        .frameOptions(frame -> frame.deny())
                        .httpStrictTransportSecurity(hsts -> hsts.includeSubDomains(true).maxAgeInSeconds(31536000)))
                .authorizeHttpRequests(auth -> auth
                        .anyRequest().hasRole("ADMIN"))
                .exceptionHandling(ex -> ex
                        .authenticationEntryPoint(entryPoint)
                        .accessDeniedHandler(accessDeniedHandler))
                .addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class);

        return http.build();
    }

    /**
     * Chain #2 — everything else. Public auth endpoints, JWT-cookie authentication for everything
     * that needs it, CSRF via double-submit cookie (see CookieService for the cookie itself),
     * and the OAuth2 login flow.
     */
    @Bean
    @Order(2)
    public SecurityFilterChain mainFilterChain(
            HttpSecurity http,
            JwtAuthenticationFilter jwtAuthenticationFilter,
            OAuth2ClientRegistrationConfig oAuth2ClientRegistrationConfig,
            CustomOAuth2UserService customOAuth2UserService,
            CustomOidcUserService customOidcUserService,
            OAuth2AuthenticationSuccessHandler oAuth2AuthenticationSuccessHandler,
            OAuth2AuthenticationFailureHandler oAuth2AuthenticationFailureHandler,
            ApiAuthenticationEntryPoint apiAuthenticationEntryPoint,
            ApiAccessDeniedHandler apiAccessDeniedHandler) throws Exception {

        CookieCsrfTokenRepository csrfTokenRepository = CookieCsrfTokenRepository.withHttpOnlyFalse();
        csrfTokenRepository.setCookieName(CookieService.CSRF_COOKIE);
        csrfTokenRepository.setHeaderName("X-CSRF-Token");
        csrfTokenRepository.setCookiePath("/");

        // Spring Security 6 uses XorCsrfTokenRequestAttributeHandler by default, which XOR-encodes
        // CSRF tokens in request attributes. Setting csrfRequestAttributeName to null opts out of
        // XOR encoding for SPA applications that send raw unmasked cookie tokens in X-CSRF-Token.
        CsrfTokenRequestAttributeHandler requestHandler = new CsrfTokenRequestAttributeHandler();
        requestHandler.setCsrfRequestAttributeName(null);

        // Computed before the authorizeHttpRequests block so the /oauth2/** permitAll rule below
        // can be conditional on it — see OAuth2DisabledIntegrationTest, which specifically
        // asserts that hitting /oauth2/authorization/google with NOTHING configured falls
        // through to the ordinary "anyRequest().authenticated()" 401, not a permitAll 404 (no
        // OAuth2 filter is registered to handle the path in that case, so blanket-permitting it
        // regardless of configuration would turn that clean 401 into an unhandled 404 instead).
        List<ClientRegistration> oauthRegistrations = oAuth2ClientRegistrationConfig.buildConfiguredRegistrations();
        boolean oauthEnabled = !oauthRegistrations.isEmpty();

        http
                .sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .cors(cors -> cors.configurationSource(corsConfigurationSource()))
                .headers(headers -> headers
                        .frameOptions(frame -> frame.deny())
                        .httpStrictTransportSecurity(hsts -> hsts.includeSubDomains(true).maxAgeInSeconds(31536000)))
                .csrf(csrf -> csrf
                        .csrfTokenRepository(csrfTokenRepository)
                        .csrfTokenRequestHandler(requestHandler)
                        // No prior session exists yet at these entry points, so there's nothing for a
                        // forged cross-site request to ride on top of in a way that matters — CSRF's threat
                        // model is "abuse an existing authenticated session", which doesn't apply here yet.
                        .ignoringRequestMatchers(
                                "/api/v1/auth/register",
                                "/api/v1/auth/login",
                                "/api/v1/auth/verify-email",
                                "/api/v1/auth/resend-verification",
                                "/api/v1/auth/forgot-password",
                                "/api/v1/auth/reset-password",
                                "/api/v1/auth/refresh"))
                .authorizeHttpRequests(auth -> {
                    auth.requestMatchers(org.springframework.http.HttpMethod.GET,
                                    "/api/v1/scenarios", "/api/v1/scenarios/**", "/api/v1/sitemap-data"
                            ).permitAll()
                            .requestMatchers(
                                    "/api/v1/auth/register", "/api/v1/auth/login", "/api/v1/auth/verify-email",
                                    "/api/v1/auth/resend-verification", "/api/v1/auth/forgot-password", "/api/v1/auth/reset-password", "/api/v1/auth/refresh",
                                    // Logout must work even with an expired or missing access token — it only
                                    // needs the refresh cookie to revoke the session. Requiring a valid access
                                    // token here would make it impossible to log out once it's expired, which
                                    // is exactly backwards for what this endpoint is for.
                                    "/api/v1/auth/logout",
                                    // Read-only, unauthenticated-by-nature: a visitor deciding which sign-in
                                    // button to show hasn't authenticated yet — that's the whole point of it.
                                    "/api/v1/auth/oauth-providers",
                                    // Anonymous play is a first-class path — permitAll here is about
                                    // AUTHORIZATION only. These stay CSRF-protected (not added to the
                                    // ignoringRequestMatchers list above), since an authenticated user
                                    // playing while logged in still has a session a forged cross-site
                                    // request could ride on top of.
                                    "/api/v1/attempts", "/api/v1/attempts/**",
                                    "/actuator/health", "/actuator/info",
                                    "/docs/**", "/api-docs/**", "/swagger-ui/**"
                            ).permitAll();

                    if (oauthEnabled) {
                        // Framework-owned OAuth2 endpoints (OAuth2AuthorizationRequestRedirectFilter /
                        // OAuth2LoginAuthenticationFilter, registered by .oauth2Login() below) already run
                        // and fully handle these paths earlier in the filter chain than this
                        // AuthorizationFilter check, so this permitAll is belt-and-suspenders rather than
                        // load-bearing today — but it makes that fact an explicit, readable rule instead of
                        // an implicit consequence of filter ordering, and it stops these routes from
                        // breaking if anything upstream ever changes (e.g. a future global "authenticate
                        // everything" policy, or a CSRF rule that starts covering GET). Both patterns match
                        // regardless of provider ("google", "github", future ones) without needing a
                        // matcher update per provider. Gated on oauthEnabled — see the comment above this
                        // method's http chain for why this can't be unconditional.
                        auth.requestMatchers("/oauth2/**", "/login/oauth2/**").permitAll();
                    }

                    auth.requestMatchers("/actuator/**").hasRole("ADMIN") // metrics/prometheus etc. — not public
                            .anyRequest().authenticated();
                })
                .exceptionHandling(ex -> ex
                        // Without this, Spring Security's default for a chain with oauth2Login configured
                        // is an HTML redirect to /login on any unauthenticated request — wrong for a JSON
                        // API. Every protected route here gets a clean 401/403 body instead.
                        .authenticationEntryPoint(apiAuthenticationEntryPoint)
                        .accessDeniedHandler(apiAccessDeniedHandler))
                .addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class);

        if (!oauthEnabled) {
            // Not an error — password auth is the primary path and works fine on its own. Logged
            // at startup (once, here) so a misconfigured deployment that expected OAuth to work
            // is at least visible in the logs, rather than a silent "the button just doesn't work."
            log.warn("OAuth2 login is disabled: no GOOGLE_OAUTH_CLIENT_ID/SECRET or "
                    + "GITHUB_OAUTH_CLIENT_ID/SECRET configured. Email/password authentication is unaffected.");
        } else {
            log.info("OAuth2 login enabled for provider(s): {}",
                    oauthRegistrations.stream().map(ClientRegistration::getRegistrationId).toList());
            ClientRegistrationRepository clientRegistrationRepository =
                    new InMemoryClientRegistrationRepository(oauthRegistrations);
            http.oauth2Login(oauth2 -> oauth2
                    .clientRegistrationRepository(clientRegistrationRepository)
                    // .userService handles registrations WITHOUT "openid" in scope (GitHub);
                    // .oidcUserService handles registrations WITH it (Google) — Spring Security
                    // picks one or the other per-registration based on that scope alone. Both
                    // must be registered or whichever path is missed falls back to Spring's
                    // stock user service, which knows nothing about our AppUser records — see
                    // CustomOidcUserService's javadoc for the bug this previously caused.
                    .userInfoEndpoint(userInfo -> userInfo
                            .userService(customOAuth2UserService)
                            .oidcUserService(customOidcUserService))
                    .successHandler(oAuth2AuthenticationSuccessHandler)
                    .failureHandler(oAuth2AuthenticationFailureHandler));
        }

        return http.build();
    }

    private CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration configuration = new CorsConfiguration();
        // Exactly the deployed frontend origin(s) — never a wildcard, since credentials (cookies) are allowed.
        configuration.setAllowedOrigins(List.of(frontendBaseUrl));
        configuration.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        configuration.setAllowedHeaders(List.of("Content-Type", "X-CSRF-Token", "X-Correlation-Id", "X-Capability-Token"));
        configuration.setAllowCredentials(true); // required for cookies to be sent cross-origin (frontend/backend on different domains)
        configuration.setMaxAge(3600L);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", configuration);
        return source;
    }
}
