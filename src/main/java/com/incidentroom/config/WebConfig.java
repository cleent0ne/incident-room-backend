package com.incidentroom.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.data.web.config.EnableSpringDataWebSupport;

import static org.springframework.data.web.config.EnableSpringDataWebSupport.PageSerializationMode.VIA_DTO;

/**
 * Without this, returning Page<T> directly from a controller (see ScenarioController#list)
 * serializes the raw PageImpl as-is, which Spring Data explicitly flags as unsupported/unstable
 * JSON shape going forward. VIA_DTO wraps every Page<T> response in Spring Data's PagedModel
 * instead — same "content" array at the top level (so no controller/DTO changes needed), but the
 * pagination metadata moves under a stable "page" object: { content: [...], page: { size, number,
 * totalElements, totalPages } }.
 */
@Configuration
@EnableSpringDataWebSupport(pageSerializationMode = VIA_DTO)
public class WebConfig {
}
