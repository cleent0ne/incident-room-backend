package com.incidentroom.repository;

import com.incidentroom.domain.LeaderboardEntry;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface LeaderboardRepository extends MongoRepository<LeaderboardEntry, String> {

    Page<LeaderboardEntry> findByScenarioSlugOrderByScoreDescElapsedSecondsAscAchievedAtAsc(String scenarioSlug, Pageable pageable);

    Optional<LeaderboardEntry> findByScenarioSlugAndUserId(String scenarioSlug, String userId);

    long countByScenarioSlug(String scenarioSlug);

    long countByScenarioSlugAndScoreLessThan(String scenarioSlug, int score);

    long countByScenarioSlugAndScoreEqualsAndElapsedSecondsGreaterThan(String scenarioSlug, int score, int elapsedSeconds);

    java.util.List<LeaderboardEntry> findByUserId(String userId);

    void deleteByUserId(String userId);
}
