package com.incidentroom.service;

import com.incidentroom.common.exception.ConflictException;
import com.incidentroom.common.exception.NotFoundException;
import com.incidentroom.domain.Difficulty;
import com.incidentroom.domain.Scenario;
import com.incidentroom.dto.AdminScenarioSummaryDto;
import com.incidentroom.dto.ScenarioSummaryDto;
import com.incidentroom.dto.SitemapEntryDto;
import com.incidentroom.repository.ScenarioRepository;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

import static com.incidentroom.config.CacheConfig.SCENARIOS_BY_SLUG_CACHE;

@Service
public class ScenarioService {

    private final ScenarioRepository scenarioRepository;
    private final MongoTemplate mongoTemplate;

    public ScenarioService(ScenarioRepository scenarioRepository, MongoTemplate mongoTemplate) {
        this.scenarioRepository = scenarioRepository;
        this.mongoTemplate = mongoTemplate;
    }

    /**
     * Catalog listing with optional filters. Built with Criteria rather than derived query method
     * variants — see the comment on ScenarioRepository for why (four independent optional filters
     * would otherwise mean four-plus method signatures for every combination).
     */
    public Page<ScenarioSummaryDto> listPublished(Difficulty difficulty, String rootCauseClass, int page, int size) {
        Criteria criteria = Criteria.where("isPublished").is(true);
        if (difficulty != null) {
            criteria = criteria.and("difficulty").is(difficulty);
        }
        if (rootCauseClass != null && !rootCauseClass.isBlank()) {
            criteria = criteria.and("rootCauseClass").is(rootCauseClass);
        }

        Pageable pageable = PageRequest.of(page, size);
        Query query = Query.query(criteria).with(pageable);

        List<Scenario> results = mongoTemplate.find(query, Scenario.class);
        long total = mongoTemplate.count(Query.query(criteria), Scenario.class);

        List<ScenarioSummaryDto> dtos = results.stream().map(ScenarioSummaryDto::from).toList();
        return new org.springframework.data.domain.PageImpl<>(dtos, pageable, total);
    }

    /**
     * The one high-volume read in the whole app — cached by slug. Only ever returns published
     * scenarios (an unpublished/draft scenario 404s exactly like a nonexistent one; see
     * ScenarioController — this is deliberate, not an oversight: draft content shouldn't be
     * distinguishable-by-response-shape from "doesn't exist" to an unauthenticated caller).
     */
    @Cacheable(value = SCENARIOS_BY_SLUG_CACHE, key = "#slug")
    public Scenario getPublishedBySlug(String slug) {
        return scenarioRepository.findBySlugAndIsPublishedTrue(slug)
                .orElseThrow(() -> new NotFoundException("Scenario not found"));
    }

    /** Lightweight projection consumed by the frontend's sitemap.xml generator. */
    /**
     * rootCauseClass is deliberately free text (see the scenario YAML schema / seed-data README)
     * — there's no backend-enforced enum, and there isn't meant to be one, since a new failure
     * category shouldn't require a deploy. This is what lets the frontend build catalog filter
     * chips from what's actually in the data instead of a hardcoded list that will drift the
     * moment someone authors a new category.
     */
    public List<String> getDistinctRootCauseClasses() {
        Query query = Query.query(Criteria.where("isPublished").is(true));
        return mongoTemplate.findDistinct(query, "rootCauseClass", Scenario.class, String.class);
    }

    public List<SitemapEntryDto> getSitemapData() {
        Query query = Query.query(Criteria.where("isPublished").is(true));
        return mongoTemplate.find(query, Scenario.class).stream()
                .map(s -> new SitemapEntryDto(s.getSlug(), s.getUpdatedAt()))
                .toList();
    }

    /**
     * Upsert-by-slug for the authoring pipeline. Preserves the existing document's id and
     * createdAt/isPublished state when updating an existing scenario — re-importing a YAML file
     * to fix a typo should never silently un-publish a live scenario or reset its identity.
     */
    @CacheEvict(value = SCENARIOS_BY_SLUG_CACHE, key = "#incoming.slug")
    public Scenario upsertScenario(Scenario incoming) {
        Optional<Scenario> existing = scenarioRepository.findBySlug(incoming.getSlug());

        Instant now = Instant.now();
        if (existing.isPresent()) {
            Scenario current = existing.get();
            incoming.setId(current.getId());
            incoming.setPublished(current.isPublished()); // publish state changes only via the explicit publish endpoint
            incoming.setCreatedAt(current.getCreatedAt());
        } else {
            incoming.setPublished(false); // every newly-imported scenario starts as a draft
            incoming.setCreatedAt(now);
        }
        incoming.setUpdatedAt(now);

        return scenarioRepository.save(incoming);
    }

    public Scenario getById(String id) {
        return scenarioRepository.findById(id)
                .orElseThrow(() -> new NotFoundException("Scenario not found"));
    }

    /**
     * Admin listing — unlike {@link #listPublished}, this includes drafts, and `published` is an
     * optional filter rather than a hard constraint. This is the endpoint that makes the backend
     * (not a given admin's browser localStorage) the source of truth for "what scenarios exist" —
     * see the product analysis, gap 3.1. Sorted by updatedAt descending so recently-touched
     * content (the thing an admin most likely wants to act on next) surfaces first.
     */
    public Page<AdminScenarioSummaryDto> listForAdmin(Boolean published, int page, int size) {
        Criteria criteria = published == null ? new Criteria() : Criteria.where("isPublished").is(published);
        Pageable pageable = PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "updatedAt"));
        Query query = Query.query(criteria).with(pageable);

        List<Scenario> results = mongoTemplate.find(query, Scenario.class);
        long total = mongoTemplate.count(Query.query(criteria), Scenario.class);

        List<AdminScenarioSummaryDto> dtos = results.stream().map(AdminScenarioSummaryDto::from).toList();
        return new PageImpl<>(dtos, pageable, total);
    }

    /**
     * Deletes a scenario outright. Deliberately refuses to delete a currently-published scenario
     * (ConflictException — unpublish first) rather than cascading an unpublish-and-delete, since a
     * live scenario disappearing out from under an in-progress attempt is a worse failure mode than
     * making the admin take two explicit steps. Draft scenarios (never published, or explicitly
     * unpublished) can be deleted freely: any historical Attempt/LeaderboardEntry rows referencing
     * this scenario already degrade gracefully by design (see AttemptService.getHistoryForUser —
     * a missing scenario title resolves to null, not an error).
     */
    @CacheEvict(value = SCENARIOS_BY_SLUG_CACHE, key = "#result.slug", condition = "#result != null")
    public Scenario deleteScenario(String id) {
        Scenario scenario = getById(id);
        if (scenario.isPublished()) {
            throw new ConflictException("Cannot delete a published scenario — unpublish it first.");
        }
        scenarioRepository.deleteById(id);
        return scenario;
    }

    /**
     * Bulk title lookup for a batch of scenario IDs — used by AttemptService's history listing so
     * rendering a page of attempts means one query, not one Scenario fetch per row (the N+1 that
     * denormalizing scenarioSlug onto Attempt was specifically meant to avoid; title is the one
     * field that wasn't worth denormalizing too, since unlike slug it's not part of any URL or
     * identity — a single bulk lookup per page is a fine trade-off for a field that's read this
     * rarely relative to how often it'd need to stay in sync if duplicated).
     */
    public Map<String, String> getTitlesByIds(Collection<String> scenarioIds) {
        if (scenarioIds.isEmpty()) {
            return Map.of();
        }
        return scenarioRepository.findAllById(scenarioIds).stream()
                .collect(Collectors.toMap(Scenario::getId, Scenario::getTitle));
    }

    @CacheEvict(value = SCENARIOS_BY_SLUG_CACHE, key = "#scenario.slug")
    public Scenario save(Scenario scenario) {
        scenario.setUpdatedAt(Instant.now());
        return scenarioRepository.save(scenario);
    }
}