package com.incidentroom.service;

import com.incidentroom.common.exception.BadRequestException;
import com.incidentroom.common.exception.NotFoundException;
import com.incidentroom.domain.ActionDefinition;
import com.incidentroom.domain.ActionKind;
import com.incidentroom.domain.Attempt;
import com.incidentroom.domain.AttemptAction;
import com.incidentroom.domain.AttemptStatus;
import com.incidentroom.domain.Outcome;
import com.incidentroom.domain.Scenario;
import com.incidentroom.dto.AttemptStateResponse;
import com.incidentroom.dto.AttemptSummaryDto;
import com.incidentroom.dto.ClaimAttemptsResponse;
import com.incidentroom.dto.ResolveAttemptResponse;
import com.incidentroom.dto.TakeActionResponse;
import com.incidentroom.dto.AttemptStatsProjection;
import com.incidentroom.dto.TelemetryEventDto;
import com.incidentroom.dto.UserStatsDto;
import com.incidentroom.domain.AppUser;
import com.incidentroom.repository.AppUserRepository;
import com.incidentroom.repository.AttemptRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.aggregation.Aggregation;
import org.springframework.data.mongodb.core.aggregation.AggregationResults;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.stereotype.Service;

import java.security.SecureRandom;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

@Service
public class AttemptService {

    /**
     * Tunable — see computeTimeBonus javadoc for the shape of the curve this caps.
     */
    private static final int TIME_BONUS_MAX_POINTS = 50;
    private static final int ABANDON_AFTER_HOURS = 24;
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    private final AttemptRepository attemptRepository;
    private final ScenarioService scenarioService;
    private final LeaderboardService leaderboardService;
    private final AppUserRepository userRepository;
    private final MongoTemplate mongoTemplate;

    public AttemptService(
            AttemptRepository attemptRepository,
            ScenarioService scenarioService,
            LeaderboardService leaderboardService,
            AppUserRepository userRepository,
            MongoTemplate mongoTemplate) {
        this.attemptRepository = attemptRepository;
        this.scenarioService = scenarioService;
        this.leaderboardService = leaderboardService;
        this.userRepository = userRepository;
        this.mongoTemplate = mongoTemplate;
    }

    public AttemptStateResponse start(String scenarioSlug, String currentUserIdOrNull) {
        Scenario scenario = scenarioService.getPublishedBySlug(scenarioSlug); // 404s for draft/nonexistent, same as the
                                                                              // public read API

        if (currentUserIdOrNull != null) {
            Optional<Attempt> existing = attemptRepository
                    .findFirstByUserIdAndScenarioSlugAndStatusOrderByStartedAtDesc(
                            currentUserIdOrNull, scenarioSlug, AttemptStatus.IN_PROGRESS);
            if (existing.isPresent()) {
                Attempt inProgress = existing.get();
                if (inProgress.getAbandonAt() != null && Instant.now().isAfter(inProgress.getAbandonAt())) {
                    inProgress.setStatus(AttemptStatus.ABANDONED);
                    attemptRepository.save(inProgress);
                } else {
                    return AttemptStateResponse.of(inProgress, scenario);
                }
            }
        }

        Instant now = Instant.now();
        Attempt attempt = Attempt.builder()
                .scenarioId(scenario.getId())
                .scenarioSlug(scenario.getSlug())
                .userId(currentUserIdOrNull)
                .capabilityToken(generateCapabilityToken())
                .status(AttemptStatus.IN_PROGRESS)
                .startedAt(now)
                .abandonAt(now.plus(ABANDON_AFTER_HOURS, ChronoUnit.HOURS))
                .build();

        Attempt saved = attemptRepository.save(attempt);
        return AttemptStateResponse.of(saved, scenario);
    }

    public AttemptStateResponse getState(String attemptId, String currentUserIdOrNull, String capabilityToken) {
        Attempt attempt = requireAccessibleAttempt(attemptId, currentUserIdOrNull, capabilityToken);
        Scenario scenario = scenarioService.getById(attempt.getScenarioId());
        return AttemptStateResponse.of(attempt, scenario);
    }

    public TakeActionResponse takeInvestigativeAction(String attemptId, String actionKey,
            String currentUserIdOrNull, String capabilityToken) {
        Attempt attempt = requireAccessibleAttempt(attemptId, currentUserIdOrNull, capabilityToken);
        requireInProgress(attempt);

        Scenario scenario = scenarioService.getById(attempt.getScenarioId());
        ActionDefinition action = findAction(scenario, actionKey, ActionKind.INVESTIGATE)
                .orElseThrow(
                        () -> new BadRequestException("Unknown investigate action for this scenario: " + actionKey));

        boolean alreadyTaken = attempt.getActionsTaken().stream().anyMatch(a -> a.getActionKey().equals(actionKey));
        if (!alreadyTaken) {
            int sequenceNo = attempt.getActionsTaken().size() + 1;
            int elapsedSoFar = computeElapsedSeconds(attempt, scenario);
            attempt.getActionsTaken().add(AttemptAction.builder()
                    .actionKey(actionKey)
                    .sequenceNo(sequenceNo)
                    .takenAtOffsetSeconds(elapsedSoFar)
                    .build());
            attemptRepository.save(attempt);
        }
        // Re-taking an already-taken action is a no-op rather than an error —
        // idempotent by design,
        // since a frontend retry (e.g. after a flaky network response) shouldn't
        // double-charge time
        // or duplicate the audit trail.

        List<TelemetryEventDto> revealed = scenario.getTelemetry() == null ? List.of()
                : scenario.getTelemetry().stream()
                        .filter(t -> actionKey.equals(t.getRevealedBy()))
                        .map(TelemetryEventDto::from)
                        .toList();

        int costSeconds = action.getCostSeconds() == null ? 0 : action.getCostSeconds();
        int totalElapsed = computeElapsedSeconds(attempt, scenario);

        return new TakeActionResponse(revealed, costSeconds, totalElapsed);
    }

    public ResolveAttemptResponse resolve(String attemptId, String remediationActionKey,
            String currentUserIdOrNull, String capabilityToken) {
        Attempt attempt = requireAccessibleAttempt(attemptId, currentUserIdOrNull, capabilityToken);
        Scenario scenario = scenarioService.getById(attempt.getScenarioId());

        if (attempt.getStatus() == AttemptStatus.COMPLETED) {
            // Idempotent: resolving an already-completed attempt re-derives the same
            // response from
            // the stored chosenRemediationActionKey + finalScore rather than erroring or
            // re-scoring.
            return buildCompletedResponse(attempt, scenario);
        }
        requireInProgress(attempt);

        ActionDefinition remediation = findAction(scenario, remediationActionKey, ActionKind.REMEDIATE)
                .orElseThrow(() -> new BadRequestException(
                        "Unknown remediation action for this scenario: " + remediationActionKey));
        Outcome outcome = remediation.getOutcome();
        if (outcome == null) {
            throw new IllegalStateException("Remediation action '" + remediationActionKey + "' on scenario '"
                    + scenario.getSlug()
                    + "' has no outcome — this scenario should never have passed publish validation");
        }

        int elapsedSeconds = computeElapsedSeconds(attempt, scenario);
        int timeBonus = computeTimeBonus(elapsedSeconds, scenario.getDurationTargetSeconds());
        int finalScore = Math.max(0, outcome.getScorePoints() + timeBonus);

        attempt.setStatus(AttemptStatus.COMPLETED);
        attempt.setCompletedAt(Instant.now());
        attempt.setElapsedSeconds(elapsedSeconds);
        attempt.setChosenRemediationActionKey(remediationActionKey);
        attempt.setFinalScore(finalScore);
        attempt.setAbandonAt(null); // clears the TTL — completed attempts are kept indefinitely, see Attempt's
                                    // javadoc
        attemptRepository.save(attempt);

        if (attempt.getUserId() != null) {
            // Single lookup reused for both the streak update and the leaderboard displayName —
            // this used to be two separate findById round trips for the same user document.
            Optional<AppUser> maybeUser = userRepository.findById(attempt.getUserId());

            maybeUser.ifPresent(user -> {
                String today = java.time.LocalDate.now(java.time.ZoneOffset.UTC).toString();
                if (!today.equals(user.getLastPlayedDate())) {
                    String yesterday = java.time.LocalDate.now(java.time.ZoneOffset.UTC).minusDays(1).toString();
                    int newStreak = yesterday.equals(user.getLastPlayedDate())
                            ? user.getCurrentStreak() + 1
                            : 1;
                    user.setCurrentStreak(newStreak);
                    user.setLastPlayedDate(today);
                    userRepository.save(user);
                }
            });

            String displayName = maybeUser.map(AppUser::getDisplayName).orElse("Player");
            leaderboardService.recordCompletion(
                    scenario.getSlug(),
                    scenario.getId(),
                    attempt.getUserId(),
                    displayName,
                    finalScore,
                    elapsedSeconds,
                    attempt.getId());
        }

        int percentile = leaderboardService.calculatePercentile(scenario.getSlug(), finalScore, elapsedSeconds);

        return buildResolveResponse(attempt, scenario, remediationActionKey, finalScore, percentile);
    }

    /**
     * Backs GET /attempts/{id}/result — lets a client (or a hard page refresh)
     * recover the full
     * debrief for an already-completed attempt without having to have kept it in
     * memory since the
     * original resolve() call. Deliberately a separate endpoint from
     * getState()/{@link
     * AttemptStateResponse} rather than folding these fields into it: the
     * play-state shape and the
     * debrief shape serve different screens, and merging them would mean every
     * play-state consumer
     * has to deal with a pile of fields that are only ever populated after the
     * fact.
     *
     * 409s (not 404) if the attempt exists but hasn't been resolved yet — that's a
     * genuinely
     * different situation from "doesn't exist," and telling a client to retry after
     * resolving is
     * more useful than a generic not-found.
     */
    public ResolveAttemptResponse getResult(String attemptId, String currentUserIdOrNull, String capabilityToken) {
        Attempt attempt = requireAccessibleAttempt(attemptId, currentUserIdOrNull, capabilityToken);
        if (attempt.getStatus() != AttemptStatus.COMPLETED) {
            throw new com.incidentroom.common.exception.ConflictException(
                    "This attempt has not been resolved yet (status: " + attempt.getStatus() + ")");
        }
        Scenario scenario = scenarioService.getById(attempt.getScenarioId());
        return buildCompletedResponse(attempt, scenario);
    }

    /**
     * Re-derives the full debrief response for an already-COMPLETED attempt from
     * its stored
     * chosenRemediationActionKey/finalScore — used by both resolve()'s idempotent
     * branch and
     * getResult(), so there's exactly one place that logic lives.
     */
    private ResolveAttemptResponse buildCompletedResponse(Attempt attempt, Scenario scenario) {
        int elapsed = attempt.getElapsedSeconds() == null ? 0 : attempt.getElapsedSeconds();
        int score = attempt.getFinalScore() == null ? 0 : attempt.getFinalScore();
        int percentile = leaderboardService.calculatePercentile(scenario.getSlug(), score, elapsed);
        return buildResolveResponse(attempt, scenario, attempt.getChosenRemediationActionKey(), score, percentile);
    }

    public void saveReflection(String attemptId, String text, String currentUserIdOrNull, String capabilityToken) {
        Attempt attempt = requireAccessibleAttempt(attemptId, currentUserIdOrNull, capabilityToken);
        requireInProgress(attempt);
        attempt.setReflectionText(text);
        attemptRepository.save(attempt);
    }

    /**
     * Attaches anonymous attempts to the now-authenticated user by capabilityToken.
     *
     * The caller proves ownership via the opaque token returned at attempt
     * creation, not the
     * MongoDB ObjectId. An attacker cannot enumerate document IDs to steal other
     * players' scores;
     * they must possess the token, which was only ever transmitted to the
     * legitimate player.
     *
     * Deliberately tolerant of a partially-bad batch: an unknown or already-expired
     * token is
     * silently skipped. Already claimed by this user is idempotent.
     */
    public ClaimAttemptsResponse claim(List<String> capabilityTokens, String currentUserId) {
        int claimedCount = 0;
        int skippedCount = 0;

        for (String capabilityToken : capabilityTokens) {
            Optional<Attempt> maybeAttempt = attemptRepository.findByCapabilityToken(capabilityToken);
            if (maybeAttempt.isEmpty()) {
                skippedCount++;
                continue;
            }

            Attempt attempt = maybeAttempt.get();
            if (attempt.getUserId() == null) {
                attempt.setUserId(currentUserId);
                attemptRepository.save(attempt);
                claimedCount++;

                if (attempt.getStatus() == AttemptStatus.COMPLETED && attempt.getFinalScore() != null) {
                    String displayName = userRepository.findById(currentUserId)
                            .map(AppUser::getDisplayName)
                            .orElse("Player");
                    int elapsed = attempt.getElapsedSeconds() == null ? 0 : attempt.getElapsedSeconds();
                    leaderboardService.recordCompletion(
                            attempt.getScenarioSlug(),
                            attempt.getScenarioId(),
                            currentUserId,
                            displayName,
                            attempt.getFinalScore(),
                            elapsed,
                            attempt.getId());
                }
            } else if (attempt.getUserId().equals(currentUserId)) {
                claimedCount++; // already theirs — idempotent, not an error
            } else {
                skippedCount++; // belongs to someone else — never overwrite
            }
        }

        return new ClaimAttemptsResponse(claimedCount, skippedCount);
    }

    /**
     * A user's attempt history, most recent first. Titles are resolved with a
     * single bulk lookup
     * across the whole page (see ScenarioService.getTitlesByIds) rather than
     * fetching each
     * attempt's scenario individually — the N+1 that denormalizing scenarioSlug
     * onto Attempt was
     * meant to avoid in the first place.
     */
    public Page<AttemptSummaryDto> getHistoryForUser(String userId, int page, int size) {
        Page<Attempt> attempts = attemptRepository.findByUserIdOrderByStartedAtDesc(userId, PageRequest.of(page, size));

        Set<String> scenarioIds = attempts.getContent().stream()
                .map(Attempt::getScenarioId)
                .collect(Collectors.toSet());
        Map<String, String> titlesByScenarioId = scenarioService.getTitlesByIds(scenarioIds);

        return attempts.map(attempt -> new AttemptSummaryDto(
                attempt.getId(),
                attempt.getScenarioSlug(),
                titlesByScenarioId.get(attempt.getScenarioId()), // null if the scenario was later deleted — degrade
                                                                 // gracefully
                attempt.getStatus(),
                attempt.getFinalScore(),
                attempt.getElapsedSeconds(),
                attempt.getStartedAt(),
                attempt.getCompletedAt()));
    }

    /**
     * Runs the completed-count/average-score aggregation directly via MongoTemplate rather than
     * a derived AttemptRepository.@Aggregation method — see the note on AttemptRepository for why
     * the interface-proxy binding for this exact "$group: { _id: null, ... }" shape isn't
     * reliable. getUniqueMappedResult() is null both when zero documents matched (empty pipeline
     * output) and is the correct, documented way to unwrap a single-group aggregation result.
     */
    public UserStatsDto getStatsForUser(String userId) {
        Aggregation aggregation = Aggregation.newAggregation(
                Aggregation.match(Criteria.where("userId").is(userId).and("status").is(AttemptStatus.COMPLETED)),
                Aggregation.group().avg("finalScore").as("averageScore").count().as("completedCount"));

        AggregationResults<AttemptStatsProjection> results =
                mongoTemplate.aggregate(aggregation, "attempts", AttemptStatsProjection.class);
        AttemptStatsProjection projection = results.getUniqueMappedResult();

        return projection == null ? UserStatsDto.empty() : UserStatsDto.from(projection);
    }

    private ResolveAttemptResponse buildResolveResponse(Attempt attempt, Scenario scenario, String remediationActionKey,
            int finalScore, int percentile) {
        ActionDefinition remediation = findAction(scenario, remediationActionKey, ActionKind.REMEDIATE)
                .orElseThrow(() -> new IllegalStateException(
                        "Attempt references a remediation action no longer present on its scenario"));
        Outcome outcome = remediation.getOutcome();
        int elapsedSeconds = attempt.getElapsedSeconds() == null ? 0 : attempt.getElapsedSeconds();
        int timeBonus = computeTimeBonus(elapsedSeconds, scenario.getDurationTargetSeconds());

        return new ResolveAttemptResponse(
                outcome.getCorrectness(),
                outcome.getScorePoints(),
                timeBonus,
                finalScore,
                outcome.getExplanation(),
                scenario.getPostmortemMarkdown(),
                elapsedSeconds,
                percentile,
                attempt.getReflectionText(),
                scenario.getSlug(),
                scenario.getTitle());
    }

    private int computeElapsedSeconds(Attempt attempt, Scenario scenario) {
        Map<String, Integer> costByKey = new HashMap<>();
        if (scenario.getActions() != null) {
            scenario.getActions().forEach(a -> costByKey.put(a.getKey(), a.getCostSeconds() == null ? 0 : a.getCostSeconds()));
        }
        return attempt.getActionsTaken().stream()
                .mapToInt(a -> costByKey.getOrDefault(a.getActionKey(), 0))
                .sum();
    }

    private int computeTimeBonus(int elapsedSeconds, int durationTargetSeconds) {
        if (durationTargetSeconds <= 0 || elapsedSeconds >= durationTargetSeconds) {
            return 0;
        }
        double remainingFraction = (durationTargetSeconds - elapsedSeconds) / (double) durationTargetSeconds;
        return (int) Math.round(remainingFraction * TIME_BONUS_MAX_POINTS);
    }

    private Optional<ActionDefinition> findAction(Scenario scenario, String key, ActionKind kind) {
        if (scenario.getActions() == null) {
            return Optional.empty();
        }
        return scenario.getActions().stream()
                .filter(a -> a.getKind() == kind && a.getKey().equals(key))
                .findFirst();
    }

    private void requireInProgress(Attempt attempt) {
        if (attempt.getStatus() != AttemptStatus.IN_PROGRESS) {
            throw new BadRequestException(
                    "This attempt is no longer in progress (status: " + attempt.getStatus() + ")");
        }
    }

    private Attempt requireAccessibleAttempt(String attemptId, String currentUserIdOrNull, String capabilityToken) {
        Attempt attempt = attemptRepository.findById(attemptId)
                .orElseThrow(() -> new NotFoundException("Attempt not found"));

        if (attempt.getUserId() != null) {
            if (!attempt.getUserId().equals(currentUserIdOrNull)) {
                throw new NotFoundException("Attempt not found");
            }
        } else {
            // Constant-time comparison — same reasoning as VerificationCodeService/AuthService's
            // token comparisons: a variable-time .equals() check leaks a timing signal proportional
            // to the matching prefix length, which is the wrong property for any bearer credential,
            // even one as high-entropy as this 256-bit capability token.
            if (capabilityToken == null || !constantTimeEquals(capabilityToken, attempt.getCapabilityToken())) {
                throw new NotFoundException("Attempt not found");
            }
        }
        return attempt;
    }

    private static String generateCapabilityToken() {
        byte[] bytes = new byte[32];
        SECURE_RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static boolean constantTimeEquals(String a, String b) {
        if (a == null || b == null) {
            return false;
        }
        return java.security.MessageDigest.isEqual(
                a.getBytes(java.nio.charset.StandardCharsets.UTF_8),
                b.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }
}