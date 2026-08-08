package com.incidentroom.repository;

import com.incidentroom.domain.Scenario;
import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.List;
import java.util.Optional;

public interface ScenarioRepository extends MongoRepository<Scenario, String> {

    Optional<Scenario> findBySlug(String slug);

    Optional<Scenario> findBySlugAndIsPublishedTrue(String slug);

    boolean existsBySlug(String slug);

    /** Daily incident lookup — uses the sparse index on `dailyDate` for efficiency. */
    Optional<Scenario> findByDailyDateAndIsPublishedTrue(String dailyDate);

    /**
     * All published scenarios in a stable (slug) order — backs the daily-incident fallback
     * rotation in DailyIncidentService for when no admin has explicitly scheduled a `dailyDate`
     * for today. Ordering by slug (rather than, say, insertion order) keeps the rotation
     * deterministic across restarts and independent of import order.
     */
    List<Scenario> findByIsPublishedTrueOrderBySlugAsc();

    // Filtered/paginated catalog listing and the sitemap projection both need flexible,
    // optional-filter queries that don't map cleanly onto derived query methods — those are
    // built with MongoTemplate/Criteria in ScenarioService instead of piling up method variants
    // here for every combination of (difficulty present/absent) x (rootCauseClass present/absent).
}
