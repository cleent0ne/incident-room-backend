package com.incidentroom.service;

import com.incidentroom.domain.LeaderboardEntry;
import com.incidentroom.dto.LeaderboardResponseDto;
import com.incidentroom.repository.LeaderboardRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class LeaderboardServiceTest {

    @Mock
    private LeaderboardRepository repository;

    private LeaderboardService leaderboardService;

    @BeforeEach
    void setUp() {
        leaderboardService = new LeaderboardService(repository);
    }

    @Test
    void recordCompletion_anonymousUser_isIgnored() {
        leaderboardService.recordCompletion("scenario-slug", "sc-1", null, "Anon", 100, 20, "att-1");
        verifyNoInteractions(repository);
    }

    @Test
    void recordCompletion_newHighscore_createsEntry() {
        when(repository.findByScenarioSlugAndUserId("slug-1", "user-1")).thenReturn(Optional.empty());

        leaderboardService.recordCompletion("slug-1", "sc-1", "user-1", "Alice", 90, 40, "att-1");

        ArgumentCaptor<LeaderboardEntry> captor = ArgumentCaptor.forClass(LeaderboardEntry.class);
        verify(repository).save(captor.capture());

        LeaderboardEntry entry = captor.getValue();
        assertThat(entry.getScenarioSlug()).isEqualTo("slug-1");
        assertThat(entry.getUserId()).isEqualTo("user-1");
        assertThat(entry.getDisplayName()).isEqualTo("Alice");
        assertThat(entry.getScore()).isEqualTo(90);
        assertThat(entry.getElapsedSeconds()).isEqualTo(40);
    }

    @Test
    void recordCompletion_lowerScore_doesNotOverwriteExisting() {
        LeaderboardEntry existing = LeaderboardEntry.builder()
                .scenarioSlug("slug-1")
                .userId("user-1")
                .score(100)
                .elapsedSeconds(30)
                .displayName("Alice")
                .build();

        when(repository.findByScenarioSlugAndUserId("slug-1", "user-1")).thenReturn(Optional.of(existing));

        leaderboardService.recordCompletion("slug-1", "sc-1", "user-1", "Alice", 80, 20, "att-2");

        verify(repository, never()).save(any());
    }

    @Test
    void recordCompletion_higherScore_updatesExisting() {
        LeaderboardEntry existing = LeaderboardEntry.builder()
                .scenarioSlug("slug-1")
                .userId("user-1")
                .score(70)
                .elapsedSeconds(50)
                .displayName("Alice")
                .build();

        when(repository.findByScenarioSlugAndUserId("slug-1", "user-1")).thenReturn(Optional.of(existing));

        leaderboardService.recordCompletion("slug-1", "sc-1", "user-1", "Alice", 95, 30, "att-3");

        verify(repository).save(existing);
        assertThat(existing.getScore()).isEqualTo(95);
        assertThat(existing.getElapsedSeconds()).isEqualTo(30);
    }

    @Test
    void getLeaderboard_returnsFormattedResponse() {
        LeaderboardEntry e1 = LeaderboardEntry.builder()
                .scenarioSlug("slug-1")
                .userId("u1")
                .displayName("Alice")
                .score(100)
                .elapsedSeconds(20)
                .achievedAt(Instant.now())
                .build();

        when(repository.findByScenarioSlugOrderByScoreDescElapsedSecondsAscAchievedAtAsc(eq("slug-1"), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(e1)));
        when(repository.countByScenarioSlug("slug-1")).thenReturn(1L);

        LeaderboardResponseDto result = leaderboardService.getLeaderboard("slug-1", 0, 10);
        assertThat(result.scenarioSlug()).isEqualTo("slug-1");
        assertThat(result.totalEntries()).isEqualTo(1);
        assertThat(result.entries()).hasSize(1);
        assertThat(result.entries().get(0).rank()).isEqualTo(1);
        assertThat(result.entries().get(0).displayName()).isEqualTo("Alice");
    }

    @Test
    void calculatePercentile_topPerformance_returns100() {
        when(repository.countByScenarioSlug("slug-1")).thenReturn(5L);
        when(repository.countByScenarioSlugAndScoreLessThan("slug-1", 100)).thenReturn(4L);
        when(repository.countByScenarioSlugAndScoreEqualsAndElapsedSecondsGreaterThan("slug-1", 100, 20)).thenReturn(0L);

        int percentile = leaderboardService.calculatePercentile("slug-1", 100, 20);
        assertThat(percentile).isEqualTo(100);
    }
}
