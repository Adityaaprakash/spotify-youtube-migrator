package com.spotifyyoutube.migrator.migration.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.spotifyyoutube.migrator.matching.engine.MatchDecision;
import com.spotifyyoutube.migrator.migration.api.dto.DestinationCandidateResponse;
import com.spotifyyoutube.migrator.migration.api.dto.MigrationPlanPreviewRequest;
import com.spotifyyoutube.migrator.migration.api.dto.MigrationPlanResponse;
import com.spotifyyoutube.migrator.migration.api.dto.MigrationPlanSummaryResponse;
import com.spotifyyoutube.migrator.migration.api.dto.MigrationPlanTrackResponse;
import com.spotifyyoutube.migrator.migration.application.plan.PlanTrackClassification;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("MigrationPlan Serialization & JSON Contract Tests")
public class MigrationPlanSerializationTest {

    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        objectMapper.registerModule(new JavaTimeModule());
    }

    @Test
    @DisplayName("Should serialize MigrationPlanResponse to expected JSON structure")
    void shouldSerializeMigrationPlanResponseCorrectly() throws Exception {
        UUID planId = UUID.fromString("11111111-1111-1111-1111-111111111111");
        Instant now = Instant.parse("2026-10-15T12:00:00Z");

        DestinationCandidateResponse candidate = new DestinationCandidateResponse(
                "yt-vid-99", "Imagine", "John Lennon", "Official Video",
                "https://i.ytimg.com/vi/yt-vid-99/default.jpg", "2024-01-01T00:00:00Z",
                Duration.ofSeconds(183)
        );

        MigrationPlanTrackResponse track0 = new MigrationPlanTrackResponse(
                0, "sp-track-1", "Imagine", "John Lennon",
                PlanTrackClassification.AUTO_MATCH, candidate, MatchDecision.MATCH,
                0.96, 0.98, List.of("Exact title", "Verified artist"), null
        );

        MigrationPlanTrackResponse track1 = new MigrationPlanTrackResponse(
                1, "sp-track-2", "Obscure B-Side", "Unknown Artist",
                PlanTrackClassification.UNMATCHED, null, MatchDecision.NO_MATCH,
                null, null, List.of("No candidate discovered"), null
        );

        MigrationPlanSummaryResponse summary = new MigrationPlanSummaryResponse(
                2, 1, 0, 1, 0, 0, 0,
                0.5, 0.0, 0.5, 0.0, 0.0, 0.0
        );

        MigrationPlanResponse planResponse = new MigrationPlanResponse(
                planId, "sp-pl-123", "Peace Songs", now, 2,
                List.of(track0, track1), summary
        );

        String json = objectMapper.writeValueAsString(planResponse);
        JsonNode root = objectMapper.readTree(json);

        // Root fields
        assertThat(root.get("planId").asText()).isEqualTo("11111111-1111-1111-1111-111111111111");
        assertThat(root.get("sourcePlaylistId").asText()).isEqualTo("sp-pl-123");
        assertThat(root.get("sourcePlaylistTitle").asText()).isEqualTo("Peace Songs");
        assertThat(root.get("totalTracks").asInt()).isEqualTo(2);

        // Tracks array
        JsonNode tracksNode = root.get("tracks");
        assertThat(tracksNode.isArray()).isTrue();
        assertThat(tracksNode).hasSize(2);

        // Track 0
        JsonNode t0 = tracksNode.get(0);
        assertThat(t0.get("position").asInt()).isEqualTo(0);
        assertThat(t0.get("sourceTrackId").asText()).isEqualTo("sp-track-1");
        assertThat(t0.get("sourceTitle").asText()).isEqualTo("Imagine");
        assertThat(t0.get("sourceArtist").asText()).isEqualTo("John Lennon");
        assertThat(t0.get("classification").asText()).isEqualTo("AUTO_MATCH");
        assertThat(t0.get("matchScore").asDouble()).isEqualTo(0.96);
        assertThat(t0.get("calibratedProbability").asDouble()).isEqualTo(0.98);
        assertThat(t0.get("selectedCandidate").get("id").asText()).isEqualTo("yt-vid-99");
        assertThat(t0.get("selectedCandidate").get("title").asText()).isEqualTo("Imagine");
        assertThat(t0.get("selectedCandidate").get("channelOrArtist").asText()).isEqualTo("John Lennon");
        assertThat(t0.get("errorMessage").isNull()).isTrue();

        // Track 1 (Null candidate and scores)
        JsonNode t1 = tracksNode.get(1);
        assertThat(t1.get("position").asInt()).isEqualTo(1);
        assertThat(t1.get("classification").asText()).isEqualTo("UNMATCHED");
        assertThat(t1.get("selectedCandidate").isNull()).isTrue();
        assertThat(t1.get("matchScore").isNull()).isTrue();
        assertThat(t1.get("calibratedProbability").isNull()).isTrue();

        // Summary
        JsonNode summaryNode = root.get("summary");
        assertThat(summaryNode.get("totalTracks").asInt()).isEqualTo(2);
        assertThat(summaryNode.get("autoMatchCount").asInt()).isEqualTo(1);
        assertThat(summaryNode.get("unmatchedCount").asInt()).isEqualTo(1);
        assertThat(summaryNode.get("autoMatchRate").asDouble()).isEqualTo(0.5);
    }

    @Test
    @DisplayName("Should deserialize MigrationPlanPreviewRequest across primary and alias field names")
    void shouldDeserializeMigrationPlanPreviewRequestAliases() throws Exception {
        // Standard field: spotifyPlaylistId
        String json1 = "{\"spotifyPlaylistId\": \"pl-standard\", \"userId\": \"22222222-2222-2222-2222-222222222222\", \"deduplicate\": false}";
        MigrationPlanPreviewRequest req1 = objectMapper.readValue(json1, MigrationPlanPreviewRequest.class);
        assertThat(req1.spotifyPlaylistId()).isEqualTo("pl-standard");
        assertThat(req1.userId()).isEqualTo(UUID.fromString("22222222-2222-2222-2222-222222222222"));
        assertThat(req1.isDeduplicate()).isFalse();

        // Alias field: sourcePlaylistId
        String json2 = "{\"sourcePlaylistId\": \"pl-alias-source\"}";
        MigrationPlanPreviewRequest req2 = objectMapper.readValue(json2, MigrationPlanPreviewRequest.class);
        assertThat(req2.spotifyPlaylistId()).isEqualTo("pl-alias-source");
        assertThat(req2.isDeduplicate()).isTrue();

        // Alias field: playlistId
        String json3 = "{\"playlistId\": \"pl-alias-generic\"}";
        MigrationPlanPreviewRequest req3 = objectMapper.readValue(json3, MigrationPlanPreviewRequest.class);
        assertThat(req3.spotifyPlaylistId()).isEqualTo("pl-alias-generic");
        assertThat(req3.isDeduplicate()).isTrue();
    }
}
