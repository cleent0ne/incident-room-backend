package com.incidentroom.service;

import com.incidentroom.domain.ActionDefinition;
import com.incidentroom.domain.ActionKind;
import com.incidentroom.domain.Correctness;
import com.incidentroom.domain.Scenario;
import com.incidentroom.domain.TelemetryEvent;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * The mandatory content-integrity gate described in the backend architecture doc §2.2. MongoDB
 * won't enforce any of this for us the way foreign keys/constraints would in a relational schema,
 * so this check is what stands in for that — and it's deliberately a hard gate on publish, not a
 * warning, since a scenario that fails these checks is actively broken for a real player (a
 * dangling revealedBy reference means a telemetry event nothing can ever reveal; no correct
 * remediation means the scenario is unsolvable).
 */
@Service
public class ScenarioValidationService {

    private static final Pattern SLUG_PATTERN = Pattern.compile("^[a-z0-9]+(-[a-z0-9]+)*$");
    private static final int MAX_META_TITLE_LENGTH = 60;
    private static final int MAX_META_DESCRIPTION_LENGTH = 160;

    public List<String> validate(Scenario scenario) {
        List<String> errors = new ArrayList<>();

        validateSlug(scenario, errors);
        validateActions(scenario, errors);
        validateTelemetryReferences(scenario, errors);
        validateSeo(scenario, errors);

        return errors;
    }

    public boolean isValid(Scenario scenario) {
        return validate(scenario).isEmpty();
    }

    private void validateSlug(Scenario scenario, List<String> errors) {
        if (scenario.getSlug() == null || !SLUG_PATTERN.matcher(scenario.getSlug()).matches()) {
            errors.add("slug must be lowercase, hyphen-separated, and contain only letters/digits (e.g. 'cache-stampede-checkout')");
        }
    }

    private void validateActions(Scenario scenario, List<String> errors) {
        List<ActionDefinition> actions = scenario.getActions();
        if (actions == null || actions.isEmpty()) {
            errors.add("scenario must define at least one action");
            return;
        }

        boolean hasCorrectRemediation = false;
        for (ActionDefinition action : actions) {
            if (action.getKey() == null || action.getKey().isBlank()) {
                errors.add("every action must have a non-empty key");
            }
            if (action.getLabel() == null || action.getLabel().isBlank()) {
                errors.add("action '" + action.getKey() + "' is missing a label");
            }
            if (action.getKind() == ActionKind.REMEDIATE) {
                if (action.getOutcome() == null) {
                    errors.add("remediate action '" + action.getKey() + "' is missing its outcome (correctness/score/explanation)");
                } else {
                    if (action.getOutcome().getExplanation() == null || action.getOutcome().getExplanation().isBlank()) {
                        errors.add("remediate action '" + action.getKey() + "' outcome is missing an explanation");
                    }
                    if (action.getOutcome().getCorrectness() == Correctness.CORRECT) {
                        hasCorrectRemediation = true;
                    }
                }
            }
        }

        if (!hasCorrectRemediation) {
            errors.add("scenario must have at least one REMEDIATE action with correctness=CORRECT — otherwise it's unsolvable");
        }
    }

    private void validateTelemetryReferences(Scenario scenario, List<String> errors) {
        Set<String> investigateKeys = new HashSet<>();
        if (scenario.getActions() != null) {
            scenario.getActions().stream()
                    .filter(a -> a.getKind() == ActionKind.INVESTIGATE)
                    .map(ActionDefinition::getKey)
                    .forEach(investigateKeys::add);
        }

        if (scenario.getTelemetry() == null) {
            return;
        }

        for (TelemetryEvent event : scenario.getTelemetry()) {
            String revealedBy = event.getRevealedBy();
            if (revealedBy != null && !investigateKeys.contains(revealedBy)) {
                errors.add("telemetry event references revealedBy='" + revealedBy
                        + "' which does not match any INVESTIGATE action key — this event could never be revealed");
            }
        }
    }

    private void validateSeo(Scenario scenario, List<String> errors) {
        if (scenario.getSeo() == null) {
            errors.add("seo metadata is required before publishing (metaTitle + metaDescription)");
            return;
        }
        String metaTitle = scenario.getSeo().getMetaTitle();
        String metaDescription = scenario.getSeo().getMetaDescription();

        if (metaTitle == null || metaTitle.isBlank()) {
            errors.add("seo.metaTitle is required");
        } else if (metaTitle.length() > MAX_META_TITLE_LENGTH) {
            errors.add("seo.metaTitle exceeds " + MAX_META_TITLE_LENGTH + " characters (was " + metaTitle.length() + ")");
        }

        if (metaDescription == null || metaDescription.isBlank()) {
            errors.add("seo.metaDescription is required");
        } else if (metaDescription.length() > MAX_META_DESCRIPTION_LENGTH) {
            errors.add("seo.metaDescription exceeds " + MAX_META_DESCRIPTION_LENGTH + " characters (was " + metaDescription.length() + ")");
        }
    }
}
