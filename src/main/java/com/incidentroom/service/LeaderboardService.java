package com.incidentroom.service;

import com.incidentroom.domain.LeaderboardEntry;
import com.incidentroom.dto.LeaderboardEntryDto;
import com.incidentroom.dto.LeaderboardResponseDto;
import com.incidentroom.repository.LeaderboardRepository;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

@Service
public class LeaderboardService {

    private final LeaderboardRepository leaderboardRepository;

    public LeaderboardService(LeaderboardRepository leaderboardRepository) {
        this.leaderboardRepository = leaderboardRepository;
    }

    public void recordCompletion(
            String scenarioSlug,
            String scenarioId,
            String userId,
            String displayName,
            int score,
            int elapsedSeconds,
            String attemptId) {

        if (userId == null || userId.isBlank()) {
            return; // Anonymous play is not listed on global user leaderboards
        }

        Optional<LeaderboardEntry> maybeEntry = leaderboardRepository.findByScenarioSlugAndUserId(scenarioSlug, userId);
        String name = (displayName != null && !displayName.isBlank()) ? displayName : "Player";

        if (maybeEntry.isEmpty()) {
            LeaderboardEntry entry = LeaderboardEntry.builder()
                    .scenarioSlug(scenarioSlug)
                    .scenarioId(scenarioId)
                    .userId(userId)
                    .displayName(name)
                    .score(score)
                    .elapsedSeconds(elapsedSeconds)
                    .attemptId(attemptId)
                    .achievedAt(Instant.now())
                    .build();
            leaderboardRepository.save(entry);
        } else {
            LeaderboardEntry existing = maybeEntry.get();
            boolean higherScore = score > existing.getScore();
            boolean sameScoreBetterTime = score == existing.getScore() && elapsedSeconds < existing.getElapsedSeconds();

            if (higherScore || sameScoreBetterTime) {
                existing.setScore(score);
                existing.setElapsedSeconds(elapsedSeconds);
                existing.setAttemptId(attemptId);
                existing.setAchievedAt(Instant.now());
                existing.setDisplayName(name);
                leaderboardRepository.save(existing);
            }
        }
    }

    public LeaderboardResponseDto getLeaderboard(String scenarioSlug, int page, int size) {
        int cappedSize = Math.min(Math.max(size, 1), 50);
        PageRequest pageable = PageRequest.of(page, cappedSize);
        Page<LeaderboardEntry> pageResult = leaderboardRepository.findByScenarioSlugOrderByScoreDescElapsedSecondsAscAchievedAtAsc(scenarioSlug, pageable);

        int startRank = page * cappedSize + 1;
        List<LeaderboardEntryDto> entries = new ArrayList<>();
        for (int i = 0; i < pageResult.getContent().size(); i++) {
            entries.add(LeaderboardEntryDto.from(pageResult.getContent().get(i), startRank + i));
        }

        long total = leaderboardRepository.countByScenarioSlug(scenarioSlug);
        return new LeaderboardResponseDto(scenarioSlug, entries, total, page, cappedSize);
    }

    public int calculatePercentile(String scenarioSlug, int score, int elapsedSeconds) {
        long totalEntries = leaderboardRepository.countByScenarioSlug(scenarioSlug);
        if (totalEntries == 0) {
            return 100;
        }

        long lowerScoreCount = leaderboardRepository.countByScenarioSlugAndScoreLessThan(scenarioSlug, score);
        long equalScoreWorseTimeCount = leaderboardRepository.countByScenarioSlugAndScoreEqualsAndElapsedSecondsGreaterThan(scenarioSlug, score, elapsedSeconds);
        long lowerPerformanceCount = lowerScoreCount + equalScoreWorseTimeCount;

        if (lowerPerformanceCount >= totalEntries) {
            return 100;
        }

        double percentile = ((double) lowerPerformanceCount / totalEntries) * 100.0;
        int rounded = (int) Math.round(percentile);

        // If top performer or only performer, guarantee 100
        if (lowerPerformanceCount == totalEntries - 1) {
            return 100;
        }
        return Math.max(1, Math.min(100, rounded));
    }
}
