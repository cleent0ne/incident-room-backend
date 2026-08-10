package com.incidentroom.service;

import com.incidentroom.domain.ActionDefinition;
import com.incidentroom.domain.ActionKind;
import com.incidentroom.domain.Attempt;
import com.incidentroom.domain.AttemptAction;
import com.incidentroom.domain.AttemptStatus;
import com.incidentroom.domain.Correctness;
import com.incidentroom.domain.Difficulty;
import com.incidentroom.domain.Outcome;
import com.incidentroom.domain.Scenario;
import com.incidentroom.dto.ResolveAttemptResponse;
import com.incidentroom.repository.AppUserRepository;
import com.incidentroom.repository.AttemptRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.mongodb.core.MongoTemplate;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * Focused unit tests for AttemptService's scoring logic — particularly {@code computeTimeBonus()}
 * which has four distinct input regions:
 *
 *   1. target <= 0 (invalid/unset) → 0
 *   2. elapsed >= target           → 0 (no penalty, just no bonus)
 *   3. elapsed = 0                 → TIME_BONUS_MAX_POINTS (full bonus)
 *   4. 0 < elapsed < target        → linear fraction of max bonus
 *
 * The time-bonus constant is 50 points (see AttemptService.TIME_BONUS_MAX_POINTS). These tests
 * verify that value and the score-floor guard (finalScore never goes negative).
 *
 * All tests use real AttemptService constructed directly to exercise the private scoring methods
 * via the public resolve() path, which is the only caller that runs the full pipeline.
 */
@ExtendWith(MockitoExtension.class)
class AttemptServiceTest {

    @Mock private AttemptRepository attemptRepository;
    @Mock private ScenarioService scenarioService;
    @Mock private LeaderboardService leaderboardService;
    @Mock private AppUserRepository userRepository;
    @Mock private MongoTemplate mongoTemplate;

    private AttemptService attemptService;

    @BeforeEach
    void setUp() {
        attemptService = new AttemptService(attemptRepository, scenarioService, leaderboardService, userRepository, mongoTemplate);
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private Scenario scenarioWithTarget(int durationTargetSeconds) {
        Outcome outcome = new Outcome();
        outcome.setCorrectness(Correctness.CORRECT);
        outcome.setScorePoints(100);
        outcome.setExplanation("Explanation.");

        ActionDefinition remediateAction = new ActionDefinition();
        remediateAction.setKey("fix-it");
        remediateAction.setKind(ActionKind.REMEDIATE);
        remediateAction.setLabel("Fix it");
        remediateAction.setCostSeconds(0);
        remediateAction.setOutcome(outcome);

        Scenario scenario = new Scenario();
        scenario.setId("sc-1");
        scenario.setSlug("test-scenario");
        scenario.setTitle("Test Scenario");
        scenario.setDifficulty(Difficulty.EASY);
        scenario.setDurationTargetSeconds(durationTargetSeconds);
        scenario.setActions(List.of(remediateAction));
        scenario.setPostmortemMarkdown("# Postmortem");
        return scenario;
    }

    /** Creates an attempt that has taken investigate actions totalling {@code elapsedCost} seconds. */
    private Attempt inProgressAttempt(String scenarioId, int elapsedCostSeconds) {
        ActionDefinition investigate = new ActionDefinition();
        investigate.setKey("check-logs");
        investigate.setKind(ActionKind.INVESTIGATE);
        investigate.setLabel("Check logs");
        investigate.setCostSeconds(elapsedCostSeconds);

        Scenario scenario = scenarioWithTarget(100);
        scenario.setActions(List.of(investigate, scenario.getActions().get(0)));

        Attempt attempt = Attempt.builder()
                .id("att-1")
                .scenarioId(scenarioId)
                .scenarioSlug("test-scenario")
                .status(AttemptStatus.IN_PROGRESS)
                .startedAt(Instant.now())
                .build();

        if (elapsedCostSeconds > 0) {
            attempt.getActionsTaken().add(
                    AttemptAction.builder().actionKey("check-logs").sequenceNo(1).takenAtOffsetSeconds(0).build()
            );
        }

        return attempt;
    }

    private void stubRepositoryAndServices(Attempt attempt, Scenario scenario) {
        when(attemptRepository.findById("att-1")).thenReturn(Optional.of(attempt));
        when(scenarioService.getById(attempt.getScenarioId())).thenReturn(scenario);
        when(attemptRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(leaderboardService.calculatePercentile(anyString(), anyInt(), anyInt())).thenReturn(75);
    }

    // ── time-bonus curve tests ────────────────────────────────────────────────

    @Test
    void timeBonus_zeroTarget_returnsZero() {
        // Guard branch: scenario with target=0 should never give a bonus (invalid target)
        Scenario scenario = scenarioWithTarget(0);
        Attempt attempt = inProgressAttempt("sc-1", 0);
        // Rebuild attempt's scenario action to match 0-target scenario
        attempt = Attempt.builder()
                .id("att-1").scenarioId("sc-1").scenarioSlug("test-scenario").capabilityToken("test-token")
                .status(AttemptStatus.IN_PROGRESS).startedAt(Instant.now()).build();
        stubRepositoryAndServices(attempt, scenario);

        ResolveAttemptResponse response = attemptService.resolve("att-1", "fix-it", null, "test-token");

        assertThat(response.timeBonus()).isEqualTo(0);
        assertThat(response.finalScore()).isEqualTo(100); // base 100 + bonus 0
    }

    @Test
    void timeBonus_elapsedEqualsTarget_returnsZero() {
        // At exactly the target, no bonus — but no penalty either
        Scenario scenario = scenarioWithTarget(100);

        ActionDefinition investigate = new ActionDefinition();
        investigate.setKey("check-logs");
        investigate.setKind(ActionKind.INVESTIGATE);
        investigate.setLabel("Check logs");
        investigate.setCostSeconds(100); // costs exactly the target
        scenario.setActions(List.of(investigate, scenario.getActions().get(0)));

        Attempt attempt = Attempt.builder()
                .id("att-1").scenarioId("sc-1").scenarioSlug("test-scenario").capabilityToken("test-token")
                .status(AttemptStatus.IN_PROGRESS).startedAt(Instant.now()).build();
        attempt.getActionsTaken().add(
                AttemptAction.builder().actionKey("check-logs").sequenceNo(1).takenAtOffsetSeconds(0).build());

        stubRepositoryAndServices(attempt, scenario);

        ResolveAttemptResponse response = attemptService.resolve("att-1", "fix-it", null, "test-token");

        assertThat(response.timeBonus()).isEqualTo(0);
        assertThat(response.finalScore()).isEqualTo(100);
    }

    @Test
    void timeBonus_elapsedOverTarget_returnsZero() {
        // Over target — same result as at-target; no penalty, no bonus
        Scenario scenario = scenarioWithTarget(100);

        ActionDefinition investigate = new ActionDefinition();
        investigate.setKey("check-logs");
        investigate.setKind(ActionKind.INVESTIGATE);
        investigate.setLabel("Check logs");
        investigate.setCostSeconds(150); // well over target
        scenario.setActions(List.of(investigate, scenario.getActions().get(0)));

        Attempt attempt = Attempt.builder()
                .id("att-1").scenarioId("sc-1").scenarioSlug("test-scenario").capabilityToken("test-token")
                .status(AttemptStatus.IN_PROGRESS).startedAt(Instant.now()).build();
        attempt.getActionsTaken().add(
                AttemptAction.builder().actionKey("check-logs").sequenceNo(1).takenAtOffsetSeconds(0).build());

        stubRepositoryAndServices(attempt, scenario);

        ResolveAttemptResponse response = attemptService.resolve("att-1", "fix-it", null, "test-token");

        assertThat(response.timeBonus()).isEqualTo(0);
        assertThat(response.finalScore()).isEqualTo(100);
    }

    @Test
    void timeBonus_zeroElapsed_returnsMaxBonus() {
        // No investigation actions taken — elapsed=0, remaining=100%, bonus=100%*50=50
        Scenario scenario = scenarioWithTarget(100);
        Attempt attempt = Attempt.builder()
                .id("att-1").scenarioId("sc-1").scenarioSlug("test-scenario").capabilityToken("test-token")
                .status(AttemptStatus.IN_PROGRESS).startedAt(Instant.now()).build();
        // No actions taken — elapsed is 0

        stubRepositoryAndServices(attempt, scenario);

        ResolveAttemptResponse response = attemptService.resolve("att-1", "fix-it", null, "test-token");

        assertThat(response.timeBonus()).isEqualTo(50);
        assertThat(response.finalScore()).isEqualTo(150); // 100 base + 50 bonus
    }

    @Test
    void timeBonus_midRange_matchesIntegrationTestFixture() {
        // elapsed=20, target=100 → remainingFraction=0.8 → bonus=round(0.8*50)=40
        // This is the same arithmetic asserted by the hand-comment in AttemptFlowIntegrationTest.
        Scenario scenario = scenarioWithTarget(100);

        ActionDefinition investigate = new ActionDefinition();
        investigate.setKey("check-logs");
        investigate.setKind(ActionKind.INVESTIGATE);
        investigate.setLabel("Check logs");
        investigate.setCostSeconds(20);
        scenario.setActions(List.of(investigate, scenario.getActions().get(0)));

        Attempt attempt = Attempt.builder()
                .id("att-1").scenarioId("sc-1").scenarioSlug("test-scenario").capabilityToken("test-token")
                .status(AttemptStatus.IN_PROGRESS).startedAt(Instant.now()).build();
        attempt.getActionsTaken().add(
                AttemptAction.builder().actionKey("check-logs").sequenceNo(1).takenAtOffsetSeconds(0).build());

        stubRepositoryAndServices(attempt, scenario);

        ResolveAttemptResponse response = attemptService.resolve("att-1", "fix-it", null, "test-token");

        assertThat(response.timeBonus()).isEqualTo(40);
        assertThat(response.finalScore()).isEqualTo(140); // 100 base + 40 bonus
    }

    @Test
    void timeBonus_justUnderTarget_returnsMinimalBonus() {
        // elapsed=99, target=100 → remainingFraction=0.01 → bonus=round(0.01*50)=round(0.5)=1 (or 0)
        // Either is acceptable — this test documents the actual rounding behaviour.
        Scenario scenario = scenarioWithTarget(100);

        ActionDefinition investigate = new ActionDefinition();
        investigate.setKey("check-logs");
        investigate.setKind(ActionKind.INVESTIGATE);
        investigate.setLabel("Check logs");
        investigate.setCostSeconds(99);
        scenario.setActions(List.of(investigate, scenario.getActions().get(0)));

        Attempt attempt = Attempt.builder()
                .id("att-1").scenarioId("sc-1").scenarioSlug("test-scenario").capabilityToken("test-token")
                .status(AttemptStatus.IN_PROGRESS).startedAt(Instant.now()).build();
        attempt.getActionsTaken().add(
                AttemptAction.builder().actionKey("check-logs").sequenceNo(1).takenAtOffsetSeconds(0).build());

        stubRepositoryAndServices(attempt, scenario);

        ResolveAttemptResponse response = attemptService.resolve("att-1", "fix-it", null, "test-token");

        // Math.round(0.5) = 1 in Java (rounds half up)
        assertThat(response.timeBonus()).isEqualTo(1);
    }

    @Test
    void finalScore_harmfulOutcome_isNegativeNotFlooredAtZero() {
        // A HARMFUL outcome's negative scorePoints is no longer floored at 0 — it should show
        // as a genuinely negative final score, so it visibly ranks below a merely-wrong
        // INCORRECT (0 points) outcome on the leaderboard instead of tying with it at a floor.
        Outcome harmfulOutcome = new Outcome();
        harmfulOutcome.setCorrectness(Correctness.HARMFUL);
        harmfulOutcome.setScorePoints(-20);
        harmfulOutcome.setExplanation("That made it worse.");

        ActionDefinition remediateAction = new ActionDefinition();
        remediateAction.setKey("fix-it");
        remediateAction.setKind(ActionKind.REMEDIATE);
        remediateAction.setLabel("Bad fix");
        remediateAction.setCostSeconds(0);
        remediateAction.setOutcome(harmfulOutcome);

        Scenario scenario = new Scenario();
        scenario.setId("sc-1");
        scenario.setSlug("test-scenario");
        scenario.setTitle("Test");
        scenario.setDifficulty(Difficulty.EASY);
        scenario.setDurationTargetSeconds(100);
        scenario.setActions(List.of(remediateAction));
        scenario.setPostmortemMarkdown("# Postmortem");

        // HARMFUL never earns a time bonus (see computeTimeBonus's javadoc), so even at
        // elapsed=0 the final score is exactly the outcome's raw -20, unmodified.
        Attempt attempt = Attempt.builder()
                .id("att-1").scenarioId("sc-1").scenarioSlug("test-scenario").capabilityToken("test-token")
                .status(AttemptStatus.IN_PROGRESS).startedAt(Instant.now()).build();

        stubRepositoryAndServices(attempt, scenario);

        ResolveAttemptResponse response = attemptService.resolve("att-1", "fix-it", null, "test-token");

        assertThat(response.timeBonus()).isEqualTo(0);
        assertThat(response.finalScore()).isEqualTo(-20);
    }

    @Test
    void timeBonus_incorrectOutcome_neverEarnsBonus() {
        // An INCORRECT outcome (0 base points) answered instantly (elapsed=0) must NOT earn the
        // speed bonus — otherwise it could outscore a slower PARTIALLY_CORRECT/CORRECT answer
        // whose base-score edge over it is smaller than TIME_BONUS_MAX_POINTS. See this test
        // class's companion fix: computeTimeBonus now gates on correctness.
        Outcome incorrectOutcome = new Outcome();
        incorrectOutcome.setCorrectness(Correctness.INCORRECT);
        incorrectOutcome.setScorePoints(0);
        incorrectOutcome.setExplanation("Wrong, but harmless.");

        ActionDefinition remediateAction = new ActionDefinition();
        remediateAction.setKey("fix-it");
        remediateAction.setKind(ActionKind.REMEDIATE);
        remediateAction.setLabel("Wrong fix");
        remediateAction.setCostSeconds(0);
        remediateAction.setOutcome(incorrectOutcome);

        Scenario scenario = new Scenario();
        scenario.setId("sc-1");
        scenario.setSlug("test-scenario");
        scenario.setTitle("Test");
        scenario.setDifficulty(Difficulty.EASY);
        scenario.setDurationTargetSeconds(100);
        scenario.setActions(List.of(remediateAction));
        scenario.setPostmortemMarkdown("# Postmortem");

        Attempt attempt = Attempt.builder()
                .id("att-1").scenarioId("sc-1").scenarioSlug("test-scenario").capabilityToken("test-token")
                .status(AttemptStatus.IN_PROGRESS).startedAt(Instant.now()).build();

        stubRepositoryAndServices(attempt, scenario);

        ResolveAttemptResponse response = attemptService.resolve("att-1", "fix-it", null, "test-token");

        assertThat(response.timeBonus()).isEqualTo(0);
        assertThat(response.finalScore()).isEqualTo(0);
    }
}