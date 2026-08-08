package com.incidentroom.dto;

import java.time.Instant;

/** Consumed by the Next.js frontend's sitemap.xml generator (see the SEO architecture doc §9.4). */
public record SitemapEntryDto(String slug, Instant updatedAt) {}
