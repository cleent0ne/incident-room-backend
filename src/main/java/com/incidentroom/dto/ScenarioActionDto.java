package com.incidentroom.dto;

import com.incidentroom.domain.ActionDefinition;
import com.incidentroom.domain.ActionKind;

/**
 * Deliberately excludes ActionDefinition.outcome — that field holds the correctness/score/
 * explanation "answer key" and must never be serialized to a client before an attempt is
 * resolved. This DTO's field list is the enforcement point: if a field isn't listed here,
 * it cannot leak, regardless of what gets added to the domain entity later.
 */
public record ScenarioActionDto(String key, ActionKind kind, String label, String description, Integer costSeconds) {

    public static ScenarioActionDto from(ActionDefinition action) {
        return new ScenarioActionDto(action.getKey(), action.getKind(), action.getLabel(),
                action.getDescription(), action.getCostSeconds());
    }
}
