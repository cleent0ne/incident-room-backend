package com.incidentroom.service;

import com.incidentroom.domain.Scenario;
import com.incidentroom.repository.ScenarioRepository;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import static com.incidentroom.config.CacheConfig.DAILY_INCIDENT_CACHE;

@Service
public class DailyIncidentService {

    private final ScenarioRepository scenarioRepository;

    public DailyIncidentService(ScenarioRepository scenarioRepository) {
        this.scenarioRepository = scenarioRepository;
    }

    /**
     * Returns today's daily scenario based on UTC date (yyyy-MM-dd).
     *
     * Prefers an admin's explicit scheduling (`Scenario.dailyDate` set via the admin
     * daily-date endpoint) when one exists for today. If nobody scheduled anything — the
     * previous behavior here — this used to return empty and the homepage's "Today's
     * Incident" panel would simply have nothing to show. That made the daily-incident feature
     * (the product's main habit-forming loop) depend on a human remembering a manual step
     * every single day, with no automated recovery if they didn't.
     *
     * The fallback: deterministically rotate through every published scenario, one per UTC
     * day, keyed off the epoch day so it's stable across restarts and across however many
     * backend instances are running (no shared state needed — every instance computes the
     * same index from the same inputs). This guarantees there's always a daily incident once
     * at least one scenario is published, while an explicit schedule still always wins when
     * both exist.
     *
     * Cached (keyed by the UTC date string, so it also rolls over at midnight independent of
     * the cache's own TTL) — this is the homepage's hottest read, and the uncached path was a
     * full published-scenario fetch on every request whenever no admin had explicitly scheduled
     * a dailyDate for today. See CacheConfig.DAILY_INCIDENT_CACHE for the staleness trade-off.
     */
    @Cacheable(value = DAILY_INCIDENT_CACHE, key = "#root.target.todayKey()")
    public Optional<Scenario> getTodaysScenario() {
        String todayStr = todayKey();

        Optional<Scenario> scheduled = scenarioRepository.findByDailyDateAndIsPublishedTrue(todayStr);
        if (scheduled.isPresent()) {
            return scheduled;
        }

        return fallbackByRotation();
    }

    private Optional<Scenario> fallbackByRotation() {
        List<Scenario> published = scenarioRepository.findByIsPublishedTrueOrderBySlugAsc();
        if (published.isEmpty()) {
            return Optional.empty();
        }

        long epochDay = LocalDate.now(ZoneOffset.UTC).toEpochDay();
        int index = (int) Math.floorMod(epochDay, (long) published.size());
        return Optional.of(published.get(index));
    }

    /** Public so the @Cacheable SpEL key expression above can invoke it via #root.target. */
    public String todayKey() {
        return LocalDate.now(ZoneOffset.UTC).toString();
    }
}
