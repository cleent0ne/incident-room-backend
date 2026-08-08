package com.incidentroom.service;

import com.incidentroom.domain.Scenario;
import com.incidentroom.repository.ScenarioRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class DailyIncidentServiceTest {

    @Mock
    private ScenarioRepository scenarioRepository;

    private DailyIncidentService dailyIncidentService;

    private final String today = LocalDate.now(ZoneOffset.UTC).toString();

    private Scenario scenario(String id, String slug) {
        return Scenario.builder().id(id).slug(slug).title(slug).isPublished(true).build();
    }

    void setUp() {
        dailyIncidentService = new DailyIncidentService(scenarioRepository);
    }

    @Test
    void explicitlyScheduledScenario_isPreferredOverFallback() {
        setUp();
        Scenario scheduled = scenario("sc-scheduled", "scheduled-incident");
        when(scenarioRepository.findByDailyDateAndIsPublishedTrue(today)).thenReturn(Optional.of(scheduled));

        Optional<Scenario> result = dailyIncidentService.getTodaysScenario();

        assertThat(result).contains(scheduled);
        // The fallback rotation query must never even run when an explicit schedule exists.
        verify(scenarioRepository, never()).findByIsPublishedTrueOrderBySlugAsc();
    }

    @Test
    void noExplicitSchedule_fallsBackToDeterministicRotation() {
        setUp();
        when(scenarioRepository.findByDailyDateAndIsPublishedTrue(today)).thenReturn(Optional.empty());
        List<Scenario> published = List.of(
                scenario("sc-1", "alpha-incident"),
                scenario("sc-2", "beta-incident"),
                scenario("sc-3", "gamma-incident"));
        when(scenarioRepository.findByIsPublishedTrueOrderBySlugAsc()).thenReturn(published);

        Optional<Scenario> result = dailyIncidentService.getTodaysScenario();

        assertThat(result).isPresent();
        assertThat(published).contains(result.get());
    }

    @Test
    void fallbackRotation_isDeterministic_sameInputsAlwaysProduceSameScenario() {
        setUp();
        when(scenarioRepository.findByDailyDateAndIsPublishedTrue(today)).thenReturn(Optional.empty());
        List<Scenario> published = List.of(
                scenario("sc-1", "alpha-incident"),
                scenario("sc-2", "beta-incident"),
                scenario("sc-3", "gamma-incident"));
        when(scenarioRepository.findByIsPublishedTrueOrderBySlugAsc()).thenReturn(published);

        Optional<Scenario> first = dailyIncidentService.getTodaysScenario();
        Optional<Scenario> second = dailyIncidentService.getTodaysScenario();

        // Same day, same published set (as multiple stateless backend instances would compute
        // independently) -> same pick, with no shared state or coordination required.
        assertThat(first).isEqualTo(second);
    }

    @Test
    void noExplicitSchedule_andNoPublishedScenarios_returnsEmpty() {
        setUp();
        when(scenarioRepository.findByDailyDateAndIsPublishedTrue(today)).thenReturn(Optional.empty());
        when(scenarioRepository.findByIsPublishedTrueOrderBySlugAsc()).thenReturn(List.of());

        Optional<Scenario> result = dailyIncidentService.getTodaysScenario();

        assertThat(result).isEmpty();
    }
}
