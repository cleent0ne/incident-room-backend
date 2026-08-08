package com.incidentroom.controller;

import com.incidentroom.common.exception.BadRequestException;
import com.incidentroom.domain.Scenario;
import com.incidentroom.dto.AdminScenarioDetailDto;
import com.incidentroom.dto.AdminScenarioSummaryDto;
import com.incidentroom.dto.ImportScenarioResponse;
import com.incidentroom.dto.PublishScenarioRequest;
import com.incidentroom.service.ScenarioService;
import com.incidentroom.service.ScenarioValidationService;
import com.incidentroom.service.ScenarioYamlImportService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.data.domain.Page;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@Tag(name = "Admin", description = "ROLE_ADMIN scenario YAML import, draft validation, publishing, and daily date scheduling")
@RestController
@RequestMapping("/api/v1/admin/scenarios")
public class AdminScenarioController {

    private final ScenarioYamlImportService importService;
    private final ScenarioService scenarioService;
    private final ScenarioValidationService validationService;

    public AdminScenarioController(
            ScenarioYamlImportService importService,
            ScenarioService scenarioService,
            ScenarioValidationService validationService) {
        this.importService = importService;
        this.scenarioService = scenarioService;
        this.validationService = validationService;
    }

    @Operation(summary = "Import scenario from raw YAML", description = "Parses and persists a scenario definition from raw YAML text. Always creates or updates a draft (unpublished).")
    @SecurityRequirement(name = "cookieAuth")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Scenario imported as draft, returns validation warnings if any"),
            @ApiResponse(responseCode = "400", description = "YAML syntax error or invalid structure"),
            @ApiResponse(responseCode = "404", description = "Not found (if caller is not authenticated or not ROLE_ADMIN)")
    })
    @PostMapping(value = "/import", consumes = MediaType.TEXT_PLAIN_VALUE)
    public ImportScenarioResponse importScenario(
            @io.swagger.v3.oas.annotations.parameters.RequestBody(description = "Raw scenario YAML content string", content = @Content(mediaType = "text/plain", schema = @Schema(type = "string")))
            @RequestBody String yamlContent) {
        ScenarioYamlImportService.ImportResult result = importService.importYaml(yamlContent);
        Scenario scenario = result.scenario();
        return new ImportScenarioResponse(scenario.getId(), scenario.getSlug(), scenario.isPublished(), result.validationWarnings());
    }

    @Operation(summary = "List scenarios (admin)", description = "Paginated list of ALL scenarios — drafts and published — with an optional published filter. This is the backend-authoritative source for what scenarios exist; the admin console should treat this as ground truth rather than any client-side cache of past imports.")
    @SecurityRequirement(name = "cookieAuth")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Paginated list of scenarios"),
            @ApiResponse(responseCode = "404", description = "Not found (if caller is not authenticated or not ROLE_ADMIN)")
    })
    @GetMapping
    public Page<AdminScenarioSummaryDto> list(
            @Parameter(description = "Filter to only published (true) or only draft (false) scenarios; omit for both") @RequestParam(required = false) Boolean published,
            @Parameter(description = "Zero-indexed page index") @RequestParam(defaultValue = "0") int page,
            @Parameter(description = "Page size (capped at 50 max)") @RequestParam(defaultValue = "20") int size) {
        int cappedSize = Math.min(Math.max(size, 1), 50);
        return scenarioService.listForAdmin(published, page, cappedSize);
    }

    @Operation(summary = "Get scenario detail (admin)", description = "Full admin view of a scenario by ID — includes the answer key (remediation outcomes) and gated telemetry references, which the public detail endpoint strips.")
    @SecurityRequirement(name = "cookieAuth")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Full admin scenario detail"),
            @ApiResponse(responseCode = "404", description = "Scenario ID not found, or caller is not authenticated / not ROLE_ADMIN")
    })
    @GetMapping("/{id}")
    public AdminScenarioDetailDto detail(@Parameter(description = "Scenario MongoDB ObjectId") @PathVariable String id) {
        return AdminScenarioDetailDto.from(scenarioService.getById(id));
    }

    @Operation(summary = "Delete scenario (admin)", description = "Permanently deletes a scenario. Refuses if the scenario is currently published — unpublish it first. Historical attempts/leaderboard entries referencing a deleted scenario degrade gracefully rather than erroring.")
    @SecurityRequirement(name = "cookieAuth")
    @ApiResponses({
            @ApiResponse(responseCode = "204", description = "Scenario deleted"),
            @ApiResponse(responseCode = "404", description = "Scenario ID not found, or caller is not authenticated / not ROLE_ADMIN"),
            @ApiResponse(responseCode = "409", description = "Scenario is currently published — unpublish before deleting")
    })
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@Parameter(description = "Scenario MongoDB ObjectId") @PathVariable String id) {
        scenarioService.deleteScenario(id);
        return ResponseEntity.noContent().build();
    }

    @Operation(summary = "Publish or unpublish scenario", description = "Publishes or unpublishes a scenario by ID. Publishing requires scenario validation check to pass.")
    @SecurityRequirement(name = "cookieAuth")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Publish state updated successfully"),
            @ApiResponse(responseCode = "400", description = "Validation errors block publishing"),
            @ApiResponse(responseCode = "404", description = "Scenario ID not found or non-admin caller")
    })
    @PatchMapping("/{id}/publish")
    public ImportScenarioResponse setPublished(
            @Parameter(description = "Scenario MongoDB ObjectId") @PathVariable String id,
            @Valid @RequestBody PublishScenarioRequest request) {
        Scenario scenario = scenarioService.getById(id);

        if (request.publish()) {
            List<String> errors = validationService.validate(scenario);
            if (!errors.isEmpty()) {
                throw new BadRequestException("Cannot publish — scenario fails validation: " + String.join("; ", errors));
            }
        }

        scenario.setPublished(request.publish());
        Scenario saved = scenarioService.save(scenario);
        return new ImportScenarioResponse(saved.getId(), saved.getSlug(), saved.isPublished(), List.of());
    }

    @Operation(summary = "Schedule daily incident date", description = "Assigns a scenario to be the designated daily incident for a specific UTC date (YYYY-MM-DD).")
    @SecurityRequirement(name = "cookieAuth")
    @ApiResponses({
            @ApiResponse(responseCode = "204", description = "Daily date assigned"),
            @ApiResponse(responseCode = "400", description = "Invalid date format"),
            @ApiResponse(responseCode = "404", description = "Scenario ID not found or non-admin caller")
    })
    @PatchMapping("/{id}/daily-date")
    public ResponseEntity<Void> setDailyDate(
            @Parameter(description = "Scenario MongoDB ObjectId") @PathVariable String id,
            @Valid @RequestBody com.incidentroom.dto.SetDailyDateRequest request) {
        Scenario scenario = scenarioService.getById(id);
        scenario.setDailyDate(request.date());
        scenarioService.save(scenario);
        return ResponseEntity.noContent().build();
    }
}
