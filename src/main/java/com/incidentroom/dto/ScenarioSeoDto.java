package com.incidentroom.dto;

import com.incidentroom.domain.ScenarioSeo;

public record ScenarioSeoDto(String metaTitle, String metaDescription, String ogImagePath) {

    public static ScenarioSeoDto from(ScenarioSeo seo) {
        if (seo == null) {
            return new ScenarioSeoDto(null, null, null);
        }
        return new ScenarioSeoDto(seo.getMetaTitle(), seo.getMetaDescription(), seo.getOgImagePath());
    }
}
