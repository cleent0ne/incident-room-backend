package com.incidentroom.actuator;

import com.mongodb.client.MongoClients;
import org.junit.jupiter.api.Test;
import org.springframework.boot.actuate.data.mongo.MongoHealthIndicator;
import org.springframework.boot.actuate.health.Health;
import org.springframework.data.mongodb.core.MongoTemplate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Proves the claim that "{@code /actuator/health} reflects real Mongo connectivity, not
 * just JVM liveness" is actually true, rather than assumed. Deliberately does NOT go
 * through a full {@code @SpringBootTest} context for the negative case: Spring Data
 * MongoDB's {@code auto-index-creation} runs during context refresh and would itself
 * throw (failing the whole context) if pointed at an unreachable Mongo at startup —
 * that's a real and separate finding (an app that can't reach Mongo at boot time won't
 * start at all, not just report unhealthy), but it means testing "Mongo becomes
 * unreachable" via full context restart is unreliable. Constructing the health
 * indicator directly against its own {@link MongoTemplate}, independent of the app's
 * context lifecycle, tests the same production code path
 * ({@code MongoHealthIndicator.doHealthCheck}) without that confound.
 */
class MongoHealthIndicatorTest {

    @Test
    void reportsDown_whenMongoIsUnreachable() {
        // Port 1 requires root to bind and nothing listens there in any normal
        // environment (local, CI, or Railway) — connection is refused immediately
        // rather than timing out, so this test stays fast without relying on a long
        // serverSelectionTimeoutMS to expire.
        MongoTemplate unreachable = new MongoTemplate(
                MongoClients.create("mongodb://127.0.0.1:1/health-indicator-test?serverSelectionTimeoutMS=1500"),
                "health-indicator-test");
        MongoHealthIndicator indicator = new MongoHealthIndicator(unreachable);

        Health health = indicator.health();

        assertThat(health.getStatus()).isEqualTo(org.springframework.boot.actuate.health.Status.DOWN);
    }

    @Test
    void reportsUp_whenMongoIsReachable() {
        // Same Mongo instance the rest of the integration suite runs against (local
        // dev docker-compose, or the CI workflow's mongodb service container) —
        // confirms the positive case uses the exact same production code path as the
        // negative case above, just against a real database.
        MongoTemplate reachable = new MongoTemplate(
                MongoClients.create("mongodb://localhost:27017/health-indicator-test?serverSelectionTimeoutMS=2000"),
                "health-indicator-test");
        MongoHealthIndicator indicator = new MongoHealthIndicator(reachable);

        Health health = indicator.health();

        assertThat(health.getStatus()).isEqualTo(org.springframework.boot.actuate.health.Status.UP);
    }
}
