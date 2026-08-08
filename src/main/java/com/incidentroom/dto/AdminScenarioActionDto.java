package com.incidentroom.dto;

import com.incidentroom.domain.ActionDefinition;
import com.incidentroom.domain.ActionKind;

/** Admin counterpart to {@link ScenarioActionDto} — includes `outcome`, since reviewing a
 *  scenario's answer key is the whole point of an admin detail view. Null on INVESTIGATE
 *  actions, same as the domain model. */
public record AdminScenarioActionDto(
        String key, ActionKind kind, String label, String description, Integer costSeconds, OutcomeDto outcome) {

    public static AdminScenarioActionDto from(ActionDefinition action) {
        return new AdminScenarioActionDto(action.getKey(), action.getKind(), action.getLabel(),
                action.getDescription(), action.getCostSeconds(), OutcomeDto.from(action.getOutcome()));
    }
}
