package com.incidentroom.service;

import com.incidentroom.domain.Attempt;
import com.incidentroom.domain.AttemptAction;
import com.incidentroom.domain.AttemptStatus;
import com.incidentroom.domain.Difficulty;
import com.incidentroom.domain.Scenario;
import com.incidentroom.dto.AttemptStateResponse;
import com.incidentroom.repository.AppUserRepository;
import com.incidentroom.repository.AttemptRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.mongodb.core.MongoTemplate;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Covers AttemptService.start()'s "one Attempt per (userId, scenario), ever"
 * invariant: a
 * logged-in user retrying a scenario they've already started/completed must
 * reset that same
 * document rather than an entirely new Attempt being inserted. See start()'s
 * javadoc for the
 * product reasoning.
 */
@ExtendWith(MockitoExtension.class)
class AttemptServiceStartTest {

    @Mock
    private AttemptRepository attemptRepository;
    @Mock
    private ScenarioService scenarioService;
    @Mock
    private LeaderboardService leaderboardService;
    @Mock
    private AppUserRepository userRepository;
    @Mock
    private MongoTemplate mongoTemplate;

    private AttemptService attemptService;

    @BeforeEach
    void setUp() {
        attemptService = new AttemptService(attemptRepository, scenarioService, leaderboardService, userRepository,
                mongoTemplate);
    }

    private Scenario publishedScenario() {
        Scenario scenario = new Scenario();
        scenario.setId("sc-1");
        scenario.setSlug("connection-pool-exhaustion");
        scenario.setTitle("Everything is slow");
        scenario.setDifficulty(Difficulty.HARD);
        scenario.setDurationTargetSeconds(900);
        scenario.setActions(List.of());
        return scenario;
    }

    @Test
    void start_noPriorAttempt_createsNewOne() {
        Scenario scenario = publishedScenario();
        when(scenarioService.getPublishedBySlug(scenario.getSlug())).thenReturn(scenario);
        when(attemptRepository.findFirstByUserIdAndScenarioSlugOrderByStartedAtDesc("user-1", scenario.getSlug()))
                .thenReturn(Optional.empty());
        when(attemptRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        AttemptStateResponse response = attemptService.start(scenario.getSlug(), "user-1");

        assertThat(response.status()).isEqualTo(AttemptStatus.IN_PROGRESS);
        verify(attemptRepository).save(any());
    }

    @Test
    void start_liveInProgressAttempt_resumesWithoutSaving() {
        Scenario scenario = publishedScenario();
        Attempt existing = Attempt.builder()
                .id("att-1").scenarioId("sc-1").scenarioSlug(scenario.getSlug()).userId("user-1")
                .status(AttemptStatus.IN_PROGRESS).startedAt(Instant.now())
                .abandonAt(Instant.now().plus(23, ChronoUnit.HOURS)) // not yet expired
                .build();

        when(scenarioService.getPublishedBySlug(scenario.getSlug())).thenReturn(scenario);
        when(attemptRepository.findFirstByUserIdAndScenarioSlugOrderByStartedAtDesc("user-1", scenario.getSlug()))
                .thenReturn(Optional.of(existing));

        AttemptStateResponse response = attemptService.start(scenario.getSlug(), "user-1");

        assertThat(response.attemptId()).isEqualTo("att-1");
        verify(attemptRepository, never()).save(any()); // pure resume — no write at all
    }

    @Test
    void start_completedAttemptExists_resetsSameDocumentInsteadOfInsertingNew() {
        Scenario scenario = publishedScenario();
        Attempt completed = Attempt.builder()
                .id("att-1").scenarioId("sc-1").scenarioSlug(scenario.getSlug()).userId("user-1")
                .status(AttemptStatus.COMPLETED)
                .startedAt(Instant.now().minus(2, ChronoUnit.DAYS))
                .completedAt(Instant.now().minus(2, ChronoUnit.DAYS))
                .elapsedSeconds(300)
                .chosenRemediationActionKey("increase_shared_pool_size")
                .finalScore(65)
                .reflectionText("I thought it was the database.")
                .actionsTaken(new java.util.ArrayList<>(List.of(
                        AttemptAction.builder().actionKey("check_overall_latency").sequenceNo(1).takenAtOffsetSeconds(0)
                                .build())))
                .build();

        when(scenarioService.getPublishedBySlug(scenario.getSlug())).thenReturn(scenario);
        when(attemptRepository.findFirstByUserIdAndScenarioSlugOrderByStartedAtDesc("user-1", scenario.getSlug()))
                .thenReturn(Optional.of(completed));
        when(attemptRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        AttemptStateResponse response = attemptService.start(scenario.getSlug(), "user-1");

        // Same attempt ID — no new document.
        assertThat(response.attemptId()).isEqualTo("att-1");
        assertThat(response.status()).isEqualTo(AttemptStatus.IN_PROGRESS);
        assertThat(response.actionsTakenKeys()).isEmpty();

        ArgumentCaptor<Attempt> saved = ArgumentCaptor.forClass(Attempt.class);
        verify(attemptRepository).save(saved.capture());
        Attempt result = saved.getValue();
        assertThat(result.getId()).isEqualTo("att-1");
        assertThat(result.getStatus()).isEqualTo(AttemptStatus.IN_PROGRESS);
        assertThat(result.getCompletedAt()).isNull();
        assertThat(result.getElapsedSeconds()).isNull();
        assertThat(result.getFinalScore()).isNull();
        assertThat(result.getChosenRemediationActionKey()).isNull();
        assertThat(result.getReflectionText()).isNull();
        assertThat(result.getActionsTaken()).isEmpty();
        assertThat(result.getAbandonAt()).isNotNull();

        // Only ever one save — never an insert of a second Attempt for this
        // user+scenario.
        verify(attemptRepository, never()).findById(any());
    }

    @Test
    void start_expiredInProgressAttemptExists_resetsSameDocument() {
        Scenario scenario = publishedScenario();
        Attempt expired = Attempt.builder()
                .id("att-1").scenarioId("sc-1").scenarioSlug(scenario.getSlug()).userId("user-1")
                .status(AttemptStatus.IN_PROGRESS)
                .startedAt(Instant.now().minus(2, ChronoUnit.DAYS))
                .abandonAt(Instant.now().minus(1, ChronoUnit.HOURS)) // already past
                .build();

        when(scenarioService.getPublishedBySlug(scenario.getSlug())).thenReturn(scenario);
        when(attemptRepository.findFirstByUserIdAndScenarioSlugOrderByStartedAtDesc("user-1", scenario.getSlug()))
                .thenReturn(Optional.of(expired));
        when(attemptRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        AttemptStateResponse response = attemptService.start(scenario.getSlug(), "user-1");

        assertThat(response.attemptId()).isEqualTo("att-1");
        assertThat(response.status()).isEqualTo(AttemptStatus.IN_PROGRESS);
        verify(attemptRepository).save(any());
    }

    @Test
    void start_anonymousUser_alwaysCreatesNewAttempt() {
        // No durable identity to dedupe against — anonymous play is unaffected by this
        // change.
        Scenario scenario = publishedScenario();
        when(scenarioService.getPublishedBySlug(scenario.getSlug())).thenReturn(scenario);
        when(attemptRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        attemptService.start(scenario.getSlug(), null);

        verify(attemptRepository, never()).findFirstByUserIdAndScenarioSlugOrderByStartedAtDesc(any(), any());
        verify(attemptRepository).save(any());
    }
}