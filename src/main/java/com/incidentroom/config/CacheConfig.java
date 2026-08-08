package com.incidentroom.config;

import com.github.benmanes.caffeine.cache.Caffeine;
import org.springframework.boot.autoconfigure.cache.CacheManagerCustomizer;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cache.caffeine.CaffeineCacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.TimeUnit;

/**
 * Scenario content is the highest-volume read in the app and changes rarely (only on admin
 * import/publish), which makes it the textbook case for a small in-process cache — see the
 * backend architecture doc §3 and §5.7 (why this matters more on a shared MongoDB Atlas M0
 * cluster than it would against dedicated resources).
 *
 * Deliberately in-process (Caffeine), not a shared cache (Redis) — correct at single-instance
 * scale; revisit only once running more than one backend instance, at which point stale-cache
 * inconsistency between instances would become a real (if minor) concern. ScenarioService is
 * responsible for evicting on any write (import/publish toggle), so staleness is bounded by
 * "did an admin action happen," not just a TTL.
 */
@Configuration
@EnableCaching
public class CacheConfig {

    public static final String SCENARIOS_BY_SLUG_CACHE = "scenariosBySlug";

    /**
     * DailyIncidentService's result — keyed by UTC date string so it also rolls over naturally at
     * midnight, independent of the shared 10-minute TTL below. Unlike SCENARIOS_BY_SLUG_CACHE,
     * nothing explicitly evicts this on admin write (scheduling a dailyDate, or a publish/
     * unpublish toggle that changes the rotation-fallback candidate list) — a bounded ≤10-minute
     * staleness window on "which scenario is today's daily incident" is an acceptable trade-off
     * for a homepage-hot, previously-uncached full-collection-fetch read.
     */
    public static final String DAILY_INCIDENT_CACHE = "dailyIncident";

    @Bean
    public CacheManagerCustomizer<CaffeineCacheManager> caffeineCacheManagerCustomizer() {
        return cacheManager -> cacheManager.setCaffeine(
                Caffeine.newBuilder()
                        .maximumSize(500) // generous relative to any realistic near-term scenario count
                        .expireAfterWrite(10, TimeUnit.MINUTES) // belt-and-suspenders alongside explicit eviction on write
                        .recordStats());
    }
}
