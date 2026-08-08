package com.incidentroom.dto;

import java.util.List;

/**
 * Field is named `published`, not `isPublished` — deliberately, to avoid depending on
 * Jackson's record-vs-classic-POJO "is" prefix stripping nuance (records preserve the exact
 * component name; regular bean getters strip "is" and lowercase, which is a well-known gotcha
 * when the two are mixed inconsistently across a codebase). Safer to just not have an "is"
 * prefix at all here than to rely on remembering which rule applies where.
 */
public record ImportScenarioResponse(String id, String slug, boolean published, List<String> validationWarnings) {}
