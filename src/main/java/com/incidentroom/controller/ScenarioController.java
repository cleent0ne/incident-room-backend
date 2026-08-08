package com.incidentroom.controller;

import com.incidentroom.domain.Difficulty;
import com.incidentroom.domain.Scenario;
import com.incidentroom.dto.LeaderboardResponseDto;
import com.incidentroom.dto.ScenarioDetailDto;
import com.incidentroom.dto.ScenarioSummaryDto;
import com.incidentroom.dto.SitemapEntryDto;
import com.incidentroom.service.LeaderboardService;
import com.incidentroom.service.ScenarioService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.data.domain.Page;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@Tag(name = "Scenarios", description = "Public scenario catalog, daily incident, root cause categories, and sitemap data")
@RestController
@RequestMapping("/api/v1")
public class ScenarioController {

    private final ScenarioService scenarioService;
    private final LeaderboardService leaderboardService;
    private final com.incidentroom.service.DailyIncidentService dailyIncidentService;

    public ScenarioController(ScenarioService scenarioService, LeaderboardService leaderboardService, com.incidentroom.service.DailyIncidentService dailyIncidentService) {
        this.scenarioService = scenarioService;
        this.leaderboardService = leaderboardService;
        this.dailyIncidentService = dailyIncidentService;
    }

    @Operation(summary = "Browse published scenario catalog", description = "Returns a paginated list of published incident scenarios with optional difficulty and root cause class filters.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Paginated list of scenario summaries")
    })
    @GetMapping("/scenarios")
    public Page<ScenarioSummaryDto> list(
            @Parameter(description = "Filter by difficulty level (EASY, MEDIUM, HARD)") @RequestParam(required = false) Difficulty difficulty,
            @Parameter(description = "Filter by root cause class (e.g. cache_stampede)") @RequestParam(required = false) String rootCauseClass,
            @Parameter(description = "Zero-indexed page index") @RequestParam(defaultValue = "0") int page,
            @Parameter(description = "Page size (capped at 50 max)") @RequestParam(defaultValue = "20") int size) {
        int cappedSize = Math.min(size, 50);
        return scenarioService.listPublished(difficulty, rootCauseClass, page, cappedSize);
    }

    @Operation(summary = "Get distinct root cause categories", description = "Returns all distinct root cause class strings present across all published scenarios.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "List of root cause category strings")
    })
    @GetMapping("/scenarios/root-cause-classes")
    public List<String> rootCauseClasses() {
        return scenarioService.getDistinctRootCauseClasses();
    }

    @Operation(summary = "Get today's daily incident", description = "Returns full details for the designated daily incident scheduled for today.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Daily scenario detail DTO"),
            @ApiResponse(responseCode = "404", description = "No daily scenario scheduled for today")
    })
    @GetMapping("/scenarios/daily")
    public ScenarioDetailDto daily() {
        Scenario scenario = dailyIncidentService.getTodaysScenario()
                .orElseThrow(() -> new com.incidentroom.common.exception.NotFoundException("No daily scenario scheduled for today"));
        return ScenarioDetailDto.from(scenario);
    }

    @Operation(summary = "Get scenario details by slug", description = "Returns public scenario details. Answer keys and gated telemetry are stripped server-side.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Scenario detail DTO"),
            @ApiResponse(responseCode = "404", description = "Scenario not found or not published")
    })
    @GetMapping("/scenarios/{slug}")
    public ScenarioDetailDto detail(@Parameter(description = "Scenario URL slug") @PathVariable String slug) {
        Scenario scenario = scenarioService.getPublishedBySlug(slug);
        return ScenarioDetailDto.from(scenario);
    }

    @Operation(summary = "Get scenario leaderboard", description = "Returns paginated high-score leaderboard entries for a specific scenario.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Paginated leaderboard response"),
            @ApiResponse(responseCode = "404", description = "Scenario not found or not published")
    })
    @GetMapping("/scenarios/{slug}/leaderboard")
    public LeaderboardResponseDto leaderboard(
            @Parameter(description = "Scenario URL slug") @PathVariable String slug,
            @Parameter(description = "Zero-indexed page index") @RequestParam(defaultValue = "0") int page,
            @Parameter(description = "Page size") @RequestParam(defaultValue = "20") int size) {
        scenarioService.getPublishedBySlug(slug);
        return leaderboardService.getLeaderboard(slug, page, size);
    }

    @Operation(summary = "Get sitemap data", description = "Returns slug and last modified timestamp for all published scenarios for sitemap generation.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "List of published scenario sitemap entries")
    })
    @GetMapping("/sitemap-data")
    public List<SitemapEntryDto> sitemapData() {
        return scenarioService.getSitemapData();
    }
}