package com.incidentroom.repository;

import com.incidentroom.domain.Attempt;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.Optional;

public interface AttemptRepository extends MongoRepository<Attempt, String> {

    Optional<Attempt> findByIdAndUserId(String id, String userId);

    Optional<Attempt> findByCapabilityToken(String capabilityToken);

    Page<Attempt> findByUserIdOrderByStartedAtDesc(String userId, Pageable pageable);

    java.util.Optional<Attempt> findFirstByUserIdAndScenarioSlugAndStatusOrderByStartedAtDesc(
            String userId, String scenarioSlug, com.incidentroom.domain.AttemptStatus status);

    java.util.List<Attempt> findByUserId(String userId);

    void deleteByUserId(String userId);

    // Note: the per-user completed-count/average-score aggregation used to live here as a
    // derived @Aggregation method returning AttemptStatsProjection directly. Spring Data
    // MongoDB's interface-proxy binding for single-object @Aggregation results is unreliable
    // for a "{ $group: { _id: null, ... } }" pipeline — it doesn't just fail on the zero-result
    // case, it fails on the non-empty case too, since every result document (whether one exists
    // or not) carries that same _id: null. See AttemptService.getStatsForUser, which now runs
    // this aggregation directly via MongoTemplate.aggregate(...).getUniqueMappedResult() instead
    // — the documented-reliable path for exactly this shape of aggregation.
}
