package com.incidentroom.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;
import io.swagger.v3.oas.models.security.SecurityScheme;
import io.swagger.v3.oas.models.servers.Server;
import io.swagger.v3.oas.models.tags.Tag;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

@Configuration
public class OpenApiConfig {

    @Value("${app.frontend-base-url:http://localhost:3000}")
    private String frontendBaseUrl;

    @Bean
    public OpenAPI incidentRoomOpenApi() {
        return new OpenAPI()
                .info(new Info()
                        .title("Incident Room API")
                        .description("Production REST API for Incident Room — a gamified SRE/DevOps incident response learning platform. " +
                                "Supports anonymous play with 256-bit capability tokens, stateless JWT cookie authentication, " +
                                "scenario catalog filtering, interactive gameplay telemetry feeds, debrief scoring, leaderboards, and admin YAML authoring.")
                        .version("1.0.0")
                        .contact(new Contact()
                                .name("Incident Room Engineering Team")
                                .url(frontendBaseUrl))
                        .license(new License()
                                .name("Proprietary")
                                .url(frontendBaseUrl)))
                .servers(List.of(
                        new Server().url("http://localhost:8080").description("Local Development Server"),
                        new Server().url("/").description("Current Server Origin")
                ))
                .tags(List.of(
                        new Tag().name("Auth").description("Authentication, email verification, session management, and OAuth2 flow"),
                        new Tag().name("Scenarios").description("Public scenario catalog, daily incident, root cause categories, and sitemap data"),
                        new Tag().name("Attempts").description("Incident gameplay loop: start attempt, investigate actions, telemetry feed, reflection, resolve, debrief, and claim"),
                        new Tag().name("User Profile").description("Authenticated user account statistics and attempt history"),
                        new Tag().name("Admin").description("ROLE_ADMIN scenario YAML import, draft validation, publishing, and daily date scheduling")
                ))
                .components(new Components()
                        .addSecuritySchemes("cookieAuth", new SecurityScheme()
                                .type(SecurityScheme.Type.APIKEY)
                                .in(SecurityScheme.In.COOKIE)
                                .name("ir_access")
                                .description("HttpOnly JWT Access Token cookie (15 min TTL)"))
                        .addSecuritySchemes("refreshCookieAuth", new SecurityScheme()
                                .type(SecurityScheme.Type.APIKEY)
                                .in(SecurityScheme.In.COOKIE)
                                .name("ir_refresh")
                                .description("HttpOnly Refresh Token cookie (30 days TTL, rotated on use)"))
                        .addSecuritySchemes("csrfHeader", new SecurityScheme()
                                .type(SecurityScheme.Type.APIKEY)
                                .in(SecurityScheme.In.HEADER)
                                .name("X-CSRF-Token")
                                .description("Double-submit CSRF protection header. Value read from JS-readable `ir_csrf` cookie."))
                        .addSecuritySchemes("capabilityTokenHeader", new SecurityScheme()
                                .type(SecurityScheme.Type.APIKEY)
                                .in(SecurityScheme.In.HEADER)
                                .name("X-Capability-Token")
                                .description("Opaque 256-bit bearer token for anonymous attempts (passed in header)")));
    }
}
