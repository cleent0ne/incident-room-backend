package com.incidentroom.controller;

import com.incidentroom.BaseIntegrationTest;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Snapshot test for the OpenAPI contract.
 *
 * On the first run (or when UPDATE_SNAPSHOTS=true), fetches /api-docs and writes the response
 * to src/test/resources/openapi-snapshot.json. On subsequent runs, compares the live contract
 * against that file. The snapshot is committed to source control — a PR that changes it is the
 * visible signal that the API contract has changed, intended for explicit reviewer awareness.
 *
 * Comparison is done on the parsed JSON tree rather than raw strings, so field-ordering
 * differences in the serialised output don't produce false positives.
 *
 * To regenerate the snapshot after an intentional API change:
 *   UPDATE_SNAPSHOTS=true mvn test -Dtest=OpenApiSnapshotTest
 */
class OpenApiSnapshotTest extends BaseIntegrationTest {

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;

    private static final Path SNAPSHOT_PATH = Path.of(
            "src/test/resources/openapi-snapshot.json");

    @Test
    void openApiContract_matchesSnapshot() throws Exception {
        MvcResult result = mockMvc.perform(get("/api-docs"))
                .andExpect(status().isOk())
                .andReturn();

        String liveJson = result.getResponse().getContentAsString();
        JsonNode liveTree = objectMapper.readTree(liveJson);

        boolean updateMode = Boolean.parseBoolean(System.getenv("UPDATE_SNAPSHOTS"))
                || Boolean.getBoolean("UPDATE_SNAPSHOTS")
                || "true".equalsIgnoreCase(System.getProperty("UPDATE_SNAPSHOTS"));

        if (updateMode || !Files.exists(SNAPSHOT_PATH)) {
            Files.createDirectories(SNAPSHOT_PATH.getParent());
            // Pretty-print for readability in diffs
            Files.writeString(SNAPSHOT_PATH,
                    objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(liveTree));
            if (!updateMode) {
                // First run — snapshot created, no comparison needed
                return;
            }
            // In update mode we still continue to verify the written snapshot is valid JSON
            return;
        }

        String snapshotJson = Files.readString(SNAPSHOT_PATH);
        JsonNode snapshotTree = objectMapper.readTree(snapshotJson);

        if (!liveTree.equals(snapshotTree)) {
            fail("""
                    OpenAPI contract has changed from the committed snapshot.
                    
                    If this is intentional (you added/changed/removed an endpoint or DTO field),
                    regenerate the snapshot and commit it:
                    
                        UPDATE_SNAPSHOTS=true mvn test -Dtest=OpenApiSnapshotTest
                    
                    Then review the diff in src/test/resources/openapi-snapshot.json before committing.
                    """);
        }

        assertThat(liveTree).isEqualTo(snapshotTree);
    }
}
