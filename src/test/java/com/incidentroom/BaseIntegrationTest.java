package com.incidentroom;

import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Base class for integration tests.
 *
 * Configures test properties (MongoDB URI, JWT secret, Frontend base URL) to point to local defaults
 * and activates the "local" profile so ConsoleEmailService is available.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("local")
public abstract class BaseIntegrationTest {

    @DynamicPropertySource
    static void defaultProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.data.mongodb.uri", () -> "mongodb://localhost:27017/incidentroom_test");
        registry.add("app.jwt.secret", () -> "integration-test-secret-value-not-used-anywhere-real-1234567890");
        registry.add("app.frontend-base-url", () -> "http://localhost:3000");
    }
}
