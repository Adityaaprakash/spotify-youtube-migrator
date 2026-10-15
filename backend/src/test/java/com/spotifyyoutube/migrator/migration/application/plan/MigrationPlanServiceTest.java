package com.spotifyyoutube.migrator.migration.application.plan;

import com.spotifyyoutube.migrator.matching.engine.MatchDecision;
import com.spotifyyoutube.migrator.migration.api.dto.MigrationPlanPreviewRequest;
import com.spotifyyoutube.migrator.migration.api.dto.MigrationPlanResponse;
import com.spotifyyoutube.migrator.migration.application.destination.model.DestinationTrackCandidate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("DefaultMigrationPlanService Unit Tests")
public class MigrationPlanServiceTest {

    @Mock
    private PlaylistMigrationPlanGenerator planGenerator;

    private DefaultMigrationPlanService planService;

    private final UUID userId = UUID.randomUUID();
    private final String playlistId = "sp-playlist-42";

    @BeforeEach
    void setUp() {
        planService = new DefaultMigrationPlanService(planGenerator);
    }

    private DestinationTrackCandidate createCandidate(String id, String title, String artist) {
        return new DestinationTrackCandidate(
                id, title, artist, "Desc " + title,
                "https://i.ytimg.com/vi/" + id + "/default.jpg",
                "2024-01-01T00:00:00Z", Duration.ofSeconds(200)
        );
    }

    @Test
    @DisplayName("Should delegate preview generation with default deduplication enabled")
    void shouldDelegateWithDefaultDeduplication() {
        MigrationPlanPreviewRequest request = new MigrationPlanPreviewRequest(playlistId);

        DestinationTrackCandidate cand = createCandidate("yt-1", "Song A", "Artist A");
        MigrationPlanTrack track0 = MigrationPlanTrack.autoMatch(
                0, "sp-1", "Song A", "Artist A", cand, 0.95, 0.98, List.of("Exact match")
        );
        UUID planId = UUID.randomUUID();
        Instant now = Instant.now();
        MigrationPlan plan = new MigrationPlan(planId, playlistId, "Summer Vibes", now, List.of(track0));

        when(planGenerator.generatePlan(userId, playlistId, true)).thenReturn(plan);

        MigrationPlanResponse response = planService.generatePreview(userId, request);

        verify(planGenerator).generatePlan(userId, playlistId, true);
        assertThat(response).isNotNull();
        assertThat(response.planId()).isEqualTo(planId);
        assertThat(response.sourcePlaylistId()).isEqualTo(playlistId);
        assertThat(response.sourcePlaylistTitle()).isEqualTo("Summer Vibes");
        assertThat(response.createdAt()).isEqualTo(now);
        assertThat(response.totalTracks()).isEqualTo(1);
        assertThat(response.tracks()).hasSize(1);

        var rTrack0 = response.tracks().get(0);
        assertThat(rTrack0.position()).isZero();
        assertThat(rTrack0.sourceTrackId()).isEqualTo("sp-1");
        assertThat(rTrack0.sourceTitle()).isEqualTo("Song A");
        assertThat(rTrack0.sourceArtist()).isEqualTo("Artist A");
        assertThat(rTrack0.classification()).isEqualTo(PlanTrackClassification.AUTO_MATCH);
        assertThat(rTrack0.selectedCandidate()).isNotNull();
        assertThat(rTrack0.selectedCandidate().id()).isEqualTo("yt-1");
        assertThat(rTrack0.matchDecision()).isEqualTo(MatchDecision.MATCH);
        assertThat(rTrack0.matchScore()).isEqualTo(0.95);
        assertThat(rTrack0.calibratedProbability()).isEqualTo(0.98);

        assertThat(response.summary().totalTracks()).isEqualTo(1);
        assertThat(response.summary().autoMatchCount()).isEqualTo(1);
        assertThat(response.summary().autoMatchRate()).isEqualTo(1.0);
    }

    @Test
    @DisplayName("Should propagate explicit deduplication false flag to plan generator")
    void shouldPropagateDeduplicationFalseFlag() {
        MigrationPlanPreviewRequest request = new MigrationPlanPreviewRequest(playlistId, userId, false);

        UUID planId = UUID.randomUUID();
        MigrationPlan emptyPlan = MigrationPlan.empty(planId, playlistId, "Empty", Instant.now());
        when(planGenerator.generatePlan(userId, playlistId, false)).thenReturn(emptyPlan);

        MigrationPlanResponse response = planService.generatePreview(userId, request);

        verify(planGenerator).generatePlan(userId, playlistId, false);
        assertThat(response.totalTracks()).isZero();
        assertThat(response.tracks()).isEmpty();
        assertThat(response.summary().totalTracks()).isZero();
    }

    @Test
    @DisplayName("Should preserve exact source playlist ordering across mixed outcome classifications")
    void shouldPreserveExactOrderingAcrossMixedOutcomes() {
        MigrationPlanPreviewRequest request = new MigrationPlanPreviewRequest(playlistId);

        DestinationTrackCandidate cand0 = createCandidate("yt-1", "Track 0", "Artist");
        DestinationTrackCandidate cand1 = createCandidate("yt-2", "Track 1", "Artist");

        MigrationPlanTrack pt0 = MigrationPlanTrack.autoMatch(0, "sp-0", "Track 0", "Artist", cand0, 0.95, 0.98, List.of());
        MigrationPlanTrack pt1 = MigrationPlanTrack.reviewRequired(1, "sp-1", "Track 1", "Artist", cand1, 0.65, 0.60, List.of());
        MigrationPlanTrack pt2 = MigrationPlanTrack.duplicate(2, "sp-2", "Track 2", "Artist", cand0, 0.95, 0.98, "Duplicate");
        MigrationPlanTrack pt3 = MigrationPlanTrack.unmatched(3, "sp-3", "Track 3", "Artist", List.of("No candidates"));
        MigrationPlanTrack pt4 = MigrationPlanTrack.failed(4, "sp-4", "Track 4", "Artist", "Failed");

        MigrationPlan plan = new MigrationPlan(UUID.randomUUID(), playlistId, "Mixed", Instant.now(), List.of(pt0, pt1, pt2, pt3, pt4));
        when(planGenerator.generatePlan(userId, playlistId, true)).thenReturn(plan);

        MigrationPlanResponse response = planService.generatePreview(userId, request);

        assertThat(response.tracks()).hasSize(5);
        for (int i = 0; i < 5; i++) {
            assertThat(response.tracks().get(i).position()).isEqualTo(i);
        }
        assertThat(response.tracks().get(0).classification()).isEqualTo(PlanTrackClassification.AUTO_MATCH);
        assertThat(response.tracks().get(1).classification()).isEqualTo(PlanTrackClassification.REVIEW_REQUIRED);
        assertThat(response.tracks().get(2).classification()).isEqualTo(PlanTrackClassification.DUPLICATE);
        assertThat(response.tracks().get(3).classification()).isEqualTo(PlanTrackClassification.UNMATCHED);
        assertThat(response.tracks().get(4).classification()).isEqualTo(PlanTrackClassification.FAILED);

        assertThat(response.summary().totalTracks()).isEqualTo(5);
        assertThat(response.summary().autoMatchCount()).isEqualTo(1);
        assertThat(response.summary().reviewRequiredCount()).isEqualTo(1);
        assertThat(response.summary().duplicateCount()).isEqualTo(1);
        assertThat(response.summary().unmatchedCount()).isEqualTo(1);
        assertThat(response.summary().failedCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("Should handle empty playlist producing valid empty response without division by zero")
    void shouldHandleEmptyPlaylistGracefully() {
        MigrationPlanPreviewRequest request = new MigrationPlanPreviewRequest(playlistId);
        MigrationPlan emptyPlan = MigrationPlan.empty(UUID.randomUUID(), playlistId, "Empty", Instant.now());

        when(planGenerator.generatePlan(userId, playlistId, true)).thenReturn(emptyPlan);

        MigrationPlanResponse response = planService.generatePreview(userId, request);

        assertThat(response.tracks()).isEmpty();
        assertThat(response.summary().totalTracks()).isZero();
        assertThat(response.summary().autoMatchRate()).isEqualTo(0.0);
        assertThat(response.summary().reviewRate()).isEqualTo(0.0);
        assertThat(response.summary().unmatchedRate()).isEqualTo(0.0);
        assertThat(response.summary().duplicateRate()).isEqualTo(0.0);
        assertThat(response.summary().failureRate()).isEqualTo(0.0);
    }

    @Test
    @DisplayName("Should reject invalid or null arguments with NullPointerException or IllegalArgumentException")
    void shouldEnforceArgumentValidations() {
        MigrationPlanPreviewRequest validRequest = new MigrationPlanPreviewRequest(playlistId);

        assertThatThrownBy(() -> planService.generatePreview(null, validRequest))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("userId must not be null");

        assertThatThrownBy(() -> planService.generatePreview(userId, null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("request must not be null");

        MigrationPlanPreviewRequest nullPlaylistRequest = new MigrationPlanPreviewRequest(null);
        assertThatThrownBy(() -> planService.generatePreview(userId, nullPlaylistRequest))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Spotify playlist ID cannot be blank");

        MigrationPlanPreviewRequest blankPlaylistRequest = new MigrationPlanPreviewRequest("   ");
        assertThatThrownBy(() -> planService.generatePreview(userId, blankPlaylistRequest))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Spotify playlist ID cannot be blank");
    }

    @Test
    @DisplayName("Should propagate underlying generator exceptions directly without swallowing")
    void shouldPropagateExceptionsFromGenerator() {
        MigrationPlanPreviewRequest request = new MigrationPlanPreviewRequest(playlistId);

        when(planGenerator.generatePlan(userId, playlistId, true))
                .thenThrow(new RuntimeException("Upstream generator error"));

        assertThatThrownBy(() -> planService.generatePreview(userId, request))
                .isInstanceOf(RuntimeException.class)
                .hasMessage("Upstream generator error");
    }

    @Test
    @DisplayName("Should expose direct domain plan generation overload")
    void shouldExposeDirectDomainPlanGenerationOverload() {
        MigrationPlan plan = MigrationPlan.empty(UUID.randomUUID(), playlistId, "Test", Instant.now());
        when(planGenerator.generatePlan(userId, playlistId, true)).thenReturn(plan);

        MigrationPlan result = planService.generatePlan(userId, playlistId, true);

        assertThat(result).isSameAs(plan);
        verify(planGenerator).generatePlan(userId, playlistId, true);
    }
}
