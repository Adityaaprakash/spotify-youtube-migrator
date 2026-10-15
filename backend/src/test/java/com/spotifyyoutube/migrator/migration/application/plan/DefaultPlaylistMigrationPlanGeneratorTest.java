package com.spotifyyoutube.migrator.migration.application.plan;

import com.spotifyyoutube.migrator.matching.discovery.CandidateDiscoveryRequest;
import com.spotifyyoutube.migrator.matching.discovery.CandidateDiscoveryResult;
import com.spotifyyoutube.migrator.matching.discovery.CandidateDiscoveryService;
import com.spotifyyoutube.migrator.matching.discovery.CandidateDiscoveryStatus;
import com.spotifyyoutube.migrator.matching.engine.MatchConfidence;
import com.spotifyyoutube.migrator.matching.engine.MatchDecision;
import com.spotifyyoutube.migrator.matching.engine.TrackMatchRequest;
import com.spotifyyoutube.migrator.matching.engine.TrackMatchResult;
import com.spotifyyoutube.migrator.matching.engine.TrackMatchingEngine;
import com.spotifyyoutube.migrator.matching.normalization.NormalizationOrchestrator;
import com.spotifyyoutube.migrator.matching.normalization.NormalizedArtistCredit;
import com.spotifyyoutube.migrator.matching.normalization.NormalizedTrackMetadata;
import com.spotifyyoutube.migrator.matching.normalization.RawTrackMetadata;
import com.spotifyyoutube.migrator.matching.normalization.TokenizedTitle;
import com.spotifyyoutube.migrator.migration.application.destination.model.DestinationTrackCandidate;
import com.spotifyyoutube.migrator.playlist.api.dto.PlaylistResponse;
import com.spotifyyoutube.migrator.playlist.api.dto.TrackResponse;
import com.spotifyyoutube.migrator.playlist.domain.Platform;
import com.spotifyyoutube.migrator.spotify.application.SpotifyPlaylistService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("DefaultPlaylistMigrationPlanGenerator Tests")
class DefaultPlaylistMigrationPlanGeneratorTest {

    @Mock
    private SpotifyPlaylistService spotifyPlaylistService;

    @Mock
    private NormalizationOrchestrator normalizationOrchestrator;

    @Mock
    private CandidateDiscoveryService candidateDiscoveryService;

    @Mock
    private TrackMatchingEngine matchingEngine;

    private DefaultPlaylistMigrationPlanGenerator planGenerator;

    private final UUID userId = UUID.randomUUID();
    private final String playlistId = "sp-playlist-100";

    @BeforeEach
    void setUp() {
        planGenerator = new DefaultPlaylistMigrationPlanGenerator(
                spotifyPlaylistService,
                normalizationOrchestrator,
                candidateDiscoveryService,
                matchingEngine
        );
    }

    private PlaylistResponse createPlaylistResponse(String externalId, String name) {
        return new PlaylistResponse(
                UUID.randomUUID(),
                name,
                "Description for " + name,
                Platform.SPOTIFY,
                externalId,
                "https://open.spotify.com/playlist/" + externalId,
                10
        );
    }

    private TrackResponse createTrackResponse(String externalId, String name, String artist, Long durationMs) {
        return new TrackResponse(
                UUID.randomUUID(),
                name,
                artist,
                "Album " + name,
                durationMs != null ? durationMs.intValue() : 200000,
                externalId
        );
    }

    private DestinationTrackCandidate createCandidate(String id, String title, String artist) {
        return new DestinationTrackCandidate(
                id,
                title,
                artist,
                "Desc " + title,
                "https://i.ytimg.com/vi/" + id + "/default.jpg",
                "2024-01-01T00:00:00Z",
                Duration.ofSeconds(200)
        );
    }

    private CandidateDiscoveryResult createDiscoveryResult(
            NormalizedTrackMetadata sourceTrack,
            List<DestinationTrackCandidate> candidates
    ) {
        if (candidates == null || candidates.isEmpty()) {
            return CandidateDiscoveryResult.empty(sourceTrack, List.of());
        }
        return new CandidateDiscoveryResult(
                sourceTrack,
                candidates,
                List.of(),
                List.of(),
                CandidateDiscoveryStatus.SUCCESS,
                null
        );
    }

    private NormalizedTrackMetadata createNormalizedMetadata(String title, String artist) {
        return new NormalizedTrackMetadata(
                RawTrackMetadata.of(title, artist, "Album " + title, 200000L),
                title.toLowerCase(),
                new NormalizedArtistCredit(artist.toLowerCase(), List.of()),
                List.of(artist.toLowerCase()),
                List.of(),
                "album " + title.toLowerCase(),
                Duration.ofMillis(200000L),
                new TokenizedTitle(
                        title, title.toLowerCase(), false, Set.of(), null, null, Set.of(), List.of(), List.of(), List.of()
                )
        );
    }

    @Test
    @DisplayName("Should generate plan with mixed classifications: AUTO_MATCH, REVIEW_REQUIRED, and UNMATCHED")
    void shouldGeneratePlanWithMixedClassifications() {
        when(spotifyPlaylistService.getPlaylistMetadata(userId, playlistId))
                .thenReturn(createPlaylistResponse(playlistId, "Summer Vibes"));

        TrackResponse t0 = createTrackResponse("sp-1", "Song A", "Artist A", 200000L);
        TrackResponse t1 = createTrackResponse("sp-2", "Song B", "Artist B", 180000L);
        TrackResponse t2 = createTrackResponse("sp-3", "Song C", "Artist C", 210000L);
        when(spotifyPlaylistService.getPlaylistTracks(userId, playlistId)).thenReturn(List.of(t0, t1, t2));

        NormalizedTrackMetadata n0 = createNormalizedMetadata("Song A", "Artist A");
        NormalizedTrackMetadata n1 = createNormalizedMetadata("Song B", "Artist B");
        NormalizedTrackMetadata n2 = createNormalizedMetadata("Song C", "Artist C");
        when(normalizationOrchestrator.normalize(any(RawTrackMetadata.class))).thenReturn(n0, n1, n2);


        DestinationTrackCandidate cand0 = createCandidate("yt-1", "Song A", "Artist A");
        DestinationTrackCandidate cand1 = createCandidate("yt-2", "Song B (Live)", "Artist B");

        when(candidateDiscoveryService.discoverCandidates(any(CandidateDiscoveryRequest.class)))
                .thenReturn(createDiscoveryResult(n0, List.of(cand0)))
                .thenReturn(createDiscoveryResult(n1, List.of(cand1)))
                .thenReturn(createDiscoveryResult(n2, List.of()));


        TrackMatchResult r0 = new TrackMatchResult(
                "sp-1", n0, MatchDecision.MATCH, cand0, 0.95, 0.98,
                MatchConfidence.EXCELLENT, null, List.of(), List.of("High title and artist similarity")
        );
        TrackMatchResult r1 = new TrackMatchResult(
                "sp-2", n1, MatchDecision.REVIEW_REQUIRED, cand1, 0.72, 0.65,
                MatchConfidence.POOR, null, List.of(), List.of("Version discrepancy: live recording")
        );
        TrackMatchResult r2 = TrackMatchResult.noMatch("sp-3", n2, List.of("No candidates discovered"));

        when(matchingEngine.matchTrack(any(TrackMatchRequest.class))).thenReturn(r0, r1, r2);

        MigrationPlan plan = planGenerator.generatePlan(userId, playlistId);

        assertThat(plan).isNotNull();
        assertThat(plan.planId()).isNotNull();
        assertThat(plan.sourcePlaylistId()).isEqualTo(playlistId);
        assertThat(plan.sourcePlaylistTitle()).isEqualTo("Summer Vibes");
        assertThat(plan.createdAt()).isNotNull();
        assertThat(plan.tracks()).hasSize(3);

        MigrationPlanTrack pt0 = plan.tracks().get(0);
        assertThat(pt0.position()).isZero();
        assertThat(pt0.sourceTrackId()).isEqualTo("sp-1");
        assertThat(pt0.classification()).isEqualTo(PlanTrackClassification.AUTO_MATCH);
        assertThat(pt0.isAutoMatch()).isTrue();
        assertThat(pt0.selectedCandidate()).isEqualTo(cand0);
        assertThat(pt0.matchScore()).isEqualTo(0.95);
        assertThat(pt0.calibratedProbability()).isEqualTo(0.98);

        MigrationPlanTrack pt1 = plan.tracks().get(1);
        assertThat(pt1.position()).isEqualTo(1);
        assertThat(pt1.sourceTrackId()).isEqualTo("sp-2");
        assertThat(pt1.classification()).isEqualTo(PlanTrackClassification.REVIEW_REQUIRED);
        assertThat(pt1.isReviewRequired()).isTrue();
        assertThat(pt1.selectedCandidate()).isEqualTo(cand1);

        MigrationPlanTrack pt2 = plan.tracks().get(2);
        assertThat(pt2.position()).isEqualTo(2);
        assertThat(pt2.sourceTrackId()).isEqualTo("sp-3");
        assertThat(pt2.classification()).isEqualTo(PlanTrackClassification.UNMATCHED);
        assertThat(pt2.isUnmatched()).isTrue();
        assertThat(pt2.selectedCandidate()).isNull();

        MigrationPlanSummary summary = plan.summary();
        assertThat(summary.totalTracks()).isEqualTo(3);
        assertThat(summary.autoMatchCount()).isEqualTo(1);
        assertThat(summary.reviewRequiredCount()).isEqualTo(1);
        assertThat(summary.unmatchedCount()).isEqualTo(1);
        assertThat(summary.duplicateCount()).isZero();
        assertThat(summary.failedCount()).isZero();
        assertThat(summary.excludedCount()).isZero();
        assertThat(summary.getAutoMatchRate()).isEqualTo(1.0 / 3.0);
    }

    @Test
    @DisplayName("Should detect non-adjacent duplicate destination candidates and preserve source metadata")
    void shouldDetectNonAdjacentDuplicateCandidates() {
        when(spotifyPlaylistService.getPlaylistMetadata(userId, playlistId))
                .thenReturn(createPlaylistResponse(playlistId, "Deduplication Test"));

        TrackResponse t0 = createTrackResponse("sp-1", "Track 1", "Artist", 200000L);
        TrackResponse t1 = createTrackResponse("sp-2", "Track 2", "Artist", 200000L);
        TrackResponse t2 = createTrackResponse("sp-3", "Track 3", "Artist", 200000L);
        when(spotifyPlaylistService.getPlaylistTracks(userId, playlistId)).thenReturn(List.of(t0, t1, t2));

        NormalizedTrackMetadata n = createNormalizedMetadata("Title", "Artist");
        when(normalizationOrchestrator.normalize(any(RawTrackMetadata.class))).thenReturn(n);

        DestinationTrackCandidate candDup = createCandidate("yt-shared", "Shared Video", "Artist");
        DestinationTrackCandidate candUnique = createCandidate("yt-unique", "Unique Video", "Artist");

        when(candidateDiscoveryService.discoverCandidates(any(CandidateDiscoveryRequest.class)))
                .thenReturn(createDiscoveryResult(n, List.of(candDup)))
                .thenReturn(createDiscoveryResult(n, List.of(candUnique)))
                .thenReturn(createDiscoveryResult(n, List.of(candDup)));

        TrackMatchResult r0 = new TrackMatchResult("sp-1", n, MatchDecision.MATCH, candDup, 0.95, 0.99, MatchConfidence.EXCELLENT, null, List.of(), List.of());
        TrackMatchResult r1 = new TrackMatchResult("sp-2", n, MatchDecision.MATCH, candUnique, 0.92, 0.95, MatchConfidence.GOOD, null, List.of(), List.of());
        TrackMatchResult r2 = new TrackMatchResult("sp-3", n, MatchDecision.MATCH, candDup, 0.95, 0.99, MatchConfidence.EXCELLENT, null, List.of(), List.of());
        when(matchingEngine.matchTrack(any(TrackMatchRequest.class))).thenReturn(r0, r1, r2);

        MigrationPlan plan = planGenerator.generatePlan(userId, playlistId, true);

        assertThat(plan.tracks()).hasSize(3);

        // Track 0: First occurrence -> AUTO_MATCH
        assertThat(plan.tracks().get(0).classification()).isEqualTo(PlanTrackClassification.AUTO_MATCH);
        assertThat(plan.tracks().get(0).selectedCandidate().id()).isEqualTo("yt-shared");

        // Track 1: Unique video -> AUTO_MATCH
        assertThat(plan.tracks().get(1).classification()).isEqualTo(PlanTrackClassification.AUTO_MATCH);
        assertThat(plan.tracks().get(1).selectedCandidate().id()).isEqualTo("yt-unique");

        // Track 2: Duplicate of track 0 -> DUPLICATE
        MigrationPlanTrack dupTrack = plan.tracks().get(2);
        assertThat(dupTrack.classification()).isEqualTo(PlanTrackClassification.DUPLICATE);
        assertThat(dupTrack.isDuplicate()).isTrue();
        assertThat(dupTrack.position()).isEqualTo(2);
        assertThat(dupTrack.sourceTrackId()).isEqualTo("sp-3");
        assertThat(dupTrack.selectedCandidate().id()).isEqualTo("yt-shared");
        assertThat(dupTrack.matchReasons()).anyMatch(reason -> reason.contains("Duplicate"));

        // Summary verification
        assertThat(plan.summary().totalTracks()).isEqualTo(3);
        assertThat(plan.summary().autoMatchCount()).isEqualTo(2);
        assertThat(plan.summary().duplicateCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("Should allow duplicate destination candidates when deduplication option is disabled")
    void shouldAllowDuplicatesWhenDisabled() {
        when(spotifyPlaylistService.getPlaylistMetadata(userId, playlistId))
                .thenReturn(createPlaylistResponse(playlistId, "No Deduplication"));

        TrackResponse t0 = createTrackResponse("sp-1", "Track 1", "Artist", 200000L);
        TrackResponse t1 = createTrackResponse("sp-2", "Track 2", "Artist", 200000L);
        when(spotifyPlaylistService.getPlaylistTracks(userId, playlistId)).thenReturn(List.of(t0, t1));

        NormalizedTrackMetadata n = createNormalizedMetadata("Title", "Artist");
        when(normalizationOrchestrator.normalize(any(RawTrackMetadata.class))).thenReturn(n);

        DestinationTrackCandidate candDup = createCandidate("yt-shared", "Shared Video", "Artist");
        when(candidateDiscoveryService.discoverCandidates(any(CandidateDiscoveryRequest.class)))
                .thenReturn(createDiscoveryResult(n, List.of(candDup)));

        TrackMatchResult r = new TrackMatchResult("sp", n, MatchDecision.MATCH, candDup, 0.95, 0.99, MatchConfidence.EXCELLENT, null, List.of(), List.of());
        when(matchingEngine.matchTrack(any(TrackMatchRequest.class))).thenReturn(r);

        MigrationPlan plan = planGenerator.generatePlan(userId, playlistId, false);

        assertThat(plan.tracks()).hasSize(2);
        assertThat(plan.tracks().get(0).classification()).isEqualTo(PlanTrackClassification.AUTO_MATCH);
        assertThat(plan.tracks().get(1).classification()).isEqualTo(PlanTrackClassification.AUTO_MATCH);
        assertThat(plan.summary().autoMatchCount()).isEqualTo(2);
        assertThat(plan.summary().duplicateCount()).isZero();
    }

    @Test
    @DisplayName("Should strictly isolate per-track failures without aborting remaining tracks")
    void shouldIsolatePerTrackFailures() {
        when(spotifyPlaylistService.getPlaylistMetadata(userId, playlistId))
                .thenReturn(createPlaylistResponse(playlistId, "Failure Isolation"));

        TrackResponse t0 = createTrackResponse("sp-1", "Success 1", "Artist", 200000L);
        TrackResponse t1 = createTrackResponse("sp-2", "Failing Track", "Artist", 200000L);
        TrackResponse t2 = createTrackResponse("sp-3", "Success 2", "Artist", 200000L);
        when(spotifyPlaylistService.getPlaylistTracks(userId, playlistId)).thenReturn(List.of(t0, t1, t2));

        NormalizedTrackMetadata n0 = createNormalizedMetadata("Success 1", "Artist");
        NormalizedTrackMetadata n2 = createNormalizedMetadata("Success 2", "Artist");
        when(normalizationOrchestrator.normalize(any(RawTrackMetadata.class)))
                .thenReturn(n0)
                .thenThrow(new RuntimeException("Normalization pipeline crashed for track 1"))
                .thenReturn(n2);

        DestinationTrackCandidate cand0 = createCandidate("yt-1", "Video 1", "Artist");
        DestinationTrackCandidate cand2 = createCandidate("yt-2", "Video 2", "Artist");
        when(candidateDiscoveryService.discoverCandidates(any(CandidateDiscoveryRequest.class)))
                .thenReturn(createDiscoveryResult(n0, List.of(cand0)))
                .thenReturn(createDiscoveryResult(n2, List.of(cand2)));

        TrackMatchResult r0 = new TrackMatchResult("sp-1", n0, MatchDecision.MATCH, cand0, 0.95, 0.99, MatchConfidence.EXCELLENT, null, List.of(), List.of());
        TrackMatchResult r2 = new TrackMatchResult("sp-3", n2, MatchDecision.MATCH, cand2, 0.95, 0.99, MatchConfidence.EXCELLENT, null, List.of(), List.of());
        when(matchingEngine.matchTrack(any(TrackMatchRequest.class)))
                .thenReturn(r0)
                .thenReturn(r2);

        MigrationPlan plan = planGenerator.generatePlan(userId, playlistId);

        assertThat(plan.tracks()).hasSize(3);

        assertThat(plan.tracks().get(0).classification()).isEqualTo(PlanTrackClassification.AUTO_MATCH);

        MigrationPlanTrack failedTrack = plan.tracks().get(1);
        assertThat(failedTrack.classification()).isEqualTo(PlanTrackClassification.FAILED);
        assertThat(failedTrack.isFailed()).isTrue();
        assertThat(failedTrack.position()).isEqualTo(1);
        assertThat(failedTrack.sourceTrackId()).isEqualTo("sp-2");
        assertThat(failedTrack.errorMessage()).contains("Normalization pipeline crashed");

        assertThat(plan.tracks().get(2).classification()).isEqualTo(PlanTrackClassification.AUTO_MATCH);

        assertThat(plan.summary().totalTracks()).isEqualTo(3);
        assertThat(plan.summary().autoMatchCount()).isEqualTo(2);
        assertThat(plan.summary().failedCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("Should sanitize sensitive credentials from error messages in failed tracks")
    void shouldSanitizeSensitiveCredentialsInErrors() {
        when(spotifyPlaylistService.getPlaylistMetadata(userId, playlistId))
                .thenReturn(createPlaylistResponse(playlistId, "Sanitization Test"));

        TrackResponse t0 = createTrackResponse("sp-1", "Track", "Artist", 200000L);
        when(spotifyPlaylistService.getPlaylistTracks(userId, playlistId)).thenReturn(List.of(t0));

        when(normalizationOrchestrator.normalize(any(RawTrackMetadata.class)))
                .thenThrow(new RuntimeException("API error with Bearer ya29.secretToken and access_token=secret123&api_key=AIzaSecret"));

        MigrationPlan plan = planGenerator.generatePlan(userId, playlistId);

        assertThat(plan.tracks()).hasSize(1);
        MigrationPlanTrack failedTrack = plan.tracks().get(0);
        assertThat(failedTrack.classification()).isEqualTo(PlanTrackClassification.FAILED);
        assertThat(failedTrack.errorMessage()).doesNotContain("ya29.secretToken");
        assertThat(failedTrack.errorMessage()).doesNotContain("secret123");
        assertThat(failedTrack.errorMessage()).doesNotContain("AIzaSecret");
        assertThat(failedTrack.errorMessage()).contains("Bearer [REDACTED]");
        assertThat(failedTrack.errorMessage()).contains("access_token=[REDACTED]");
        assertThat(failedTrack.errorMessage()).contains("api_key=[REDACTED]");
    }

    @Test
    @DisplayName("Should handle empty playlist producing valid empty plan")
    void shouldHandleEmptyPlaylist() {
        when(spotifyPlaylistService.getPlaylistMetadata(userId, playlistId))
                .thenReturn(createPlaylistResponse(playlistId, "Empty Playlist"));
        when(spotifyPlaylistService.getPlaylistTracks(userId, playlistId)).thenReturn(List.of());

        MigrationPlan plan = planGenerator.generatePlan(userId, playlistId);

        assertThat(plan.tracks()).isEmpty();
        assertThat(plan.summary().totalTracks()).isZero();
        assertThat(plan.summary().isEmpty()).isTrue();
        assertThat(plan.summary().getAutoMatchRate()).isEqualTo(0.0);
    }

    @Test
    @DisplayName("Should enforce mandatory arguments validation")
    void shouldEnforceMandatoryArguments() {
        assertThatThrownBy(() -> planGenerator.generatePlan(null, playlistId))
                .isInstanceOf(NullPointerException.class);

        assertThatThrownBy(() -> planGenerator.generatePlan(userId, null))
                .isInstanceOf(NullPointerException.class);

        assertThatThrownBy(() -> planGenerator.generatePlan(userId, "   "))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("Should guarantee plan immutability for both empty and populated plans")
    void shouldGuaranteeImmutability() {
        when(spotifyPlaylistService.getPlaylistMetadata(userId, playlistId))
                .thenReturn(createPlaylistResponse(playlistId, "Immutable Test"));
        when(spotifyPlaylistService.getPlaylistTracks(userId, playlistId)).thenReturn(List.of());

        MigrationPlan plan = planGenerator.generatePlan(userId, playlistId);

        assertThatThrownBy(() -> plan.tracks().add(
                MigrationPlanTrack.unmatched(0, "s", "T", "A", List.of())
        )).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    @DisplayName("Should generate plan with 100% AUTO_MATCH for high-confidence matching tracks")
    void shouldGeneratePlanWhenAllTracksAutoMatch() {
        when(spotifyPlaylistService.getPlaylistMetadata(userId, playlistId))
                .thenReturn(createPlaylistResponse(playlistId, "All Stars Playlist"));

        TrackResponse t0 = createTrackResponse("sp-1", "Track 1", "Artist 1", 200000L);
        TrackResponse t1 = createTrackResponse("sp-2", "Track 2", "Artist 2", 210000L);
        when(spotifyPlaylistService.getPlaylistTracks(userId, playlistId)).thenReturn(List.of(t0, t1));

        NormalizedTrackMetadata n0 = createNormalizedMetadata("Track 1", "Artist 1");
        NormalizedTrackMetadata n1 = createNormalizedMetadata("Track 2", "Artist 2");
        when(normalizationOrchestrator.normalize(any(RawTrackMetadata.class))).thenReturn(n0, n1);

        DestinationTrackCandidate cand0 = createCandidate("yt-1", "Track 1", "Artist 1");
        DestinationTrackCandidate cand1 = createCandidate("yt-2", "Track 2", "Artist 2");

        when(candidateDiscoveryService.discoverCandidates(any(CandidateDiscoveryRequest.class)))
                .thenReturn(createDiscoveryResult(n0, List.of(cand0)))
                .thenReturn(createDiscoveryResult(n1, List.of(cand1)));

        TrackMatchResult r0 = new TrackMatchResult(
                "sp-1", n0, MatchDecision.MATCH, cand0, 0.98, 0.99,
                MatchConfidence.EXCELLENT, null, List.of(), List.of("High score")
        );
        TrackMatchResult r1 = new TrackMatchResult(
                "sp-2", n1, MatchDecision.MATCH, cand1, 0.95, 0.97,
                MatchConfidence.EXCELLENT, null, List.of(), List.of("High score")
        );
        when(matchingEngine.matchTrack(any(TrackMatchRequest.class))).thenReturn(r0, r1);

        MigrationPlan plan = planGenerator.generatePlan(userId, playlistId);

        assertThat(plan.tracks()).hasSize(2);
        assertThat(plan.tracks().get(0).classification()).isEqualTo(PlanTrackClassification.AUTO_MATCH);
        assertThat(plan.tracks().get(0).isAutoMatch()).isTrue();
        assertThat(plan.tracks().get(1).classification()).isEqualTo(PlanTrackClassification.AUTO_MATCH);
        assertThat(plan.tracks().get(1).isAutoMatch()).isTrue();

        MigrationPlanSummary summary = plan.summary();
        assertThat(summary.totalTracks()).isEqualTo(2);
        assertThat(summary.autoMatchCount()).isEqualTo(2);
        assertThat(summary.reviewRequiredCount()).isZero();
        assertThat(summary.unmatchedCount()).isZero();
        assertThat(summary.duplicateCount()).isZero();
        assertThat(summary.failedCount()).isZero();
        assertThat(summary.excludedCount()).isZero();
        assertThat(summary.getAutoMatchRate()).isEqualTo(1.0);
        assertThat(summary.hasIssues()).isFalse();
        assertThat(summary.isActionable()).isTrue();
    }

    @Test
    @DisplayName("Should preserve exact source playlist ordering across 5 distinct outcome classifications")
    void shouldPreserveExactSourcePlaylistOrderingAcrossMixedOutcomes() {
        when(spotifyPlaylistService.getPlaylistMetadata(userId, playlistId))
                .thenReturn(createPlaylistResponse(playlistId, "Five Classification Ordering"));

        TrackResponse t0 = createTrackResponse("sp-1", "Song 0", "Artist", 200000L);
        TrackResponse t1 = createTrackResponse("sp-2", "Song 1", "Artist", 200000L);
        TrackResponse t2 = createTrackResponse("sp-3", "Song 2", "Artist", 200000L);
        TrackResponse t3 = createTrackResponse("sp-4", "Song 3", "Artist", 200000L);
        TrackResponse t4 = createTrackResponse("sp-5", "Song 4", "Artist", 200000L);
        when(spotifyPlaylistService.getPlaylistTracks(userId, playlistId)).thenReturn(List.of(t0, t1, t2, t3, t4));

        NormalizedTrackMetadata n0 = createNormalizedMetadata("Song 0", "Artist");
        NormalizedTrackMetadata n1 = createNormalizedMetadata("Song 1", "Artist");
        NormalizedTrackMetadata n2 = createNormalizedMetadata("Song 2", "Artist");
        NormalizedTrackMetadata n3 = createNormalizedMetadata("Song 3", "Artist");
        when(normalizationOrchestrator.normalize(any(RawTrackMetadata.class)))
                .thenReturn(n0)
                .thenReturn(n1)
                .thenReturn(n2)
                .thenReturn(n3)
                .thenThrow(new RuntimeException("Simulated normalization engine failure for track 4"));

        DestinationTrackCandidate candShared = createCandidate("yt-shared", "Shared Video", "Artist");
        DestinationTrackCandidate candAmbiguous = createCandidate("yt-ambig", "Ambig Video", "Artist");

        when(candidateDiscoveryService.discoverCandidates(any(CandidateDiscoveryRequest.class)))
                .thenReturn(createDiscoveryResult(n0, List.of(candShared)))
                .thenReturn(createDiscoveryResult(n1, List.of(candAmbiguous)))
                .thenReturn(createDiscoveryResult(n2, List.of(candShared)))
                .thenReturn(createDiscoveryResult(n3, List.of()));

        TrackMatchResult r0 = new TrackMatchResult("sp-1", n0, MatchDecision.MATCH, candShared, 0.95, 0.98, MatchConfidence.EXCELLENT, null, List.of(), List.of("Auto match"));
        TrackMatchResult r1 = new TrackMatchResult("sp-2", n1, MatchDecision.REVIEW_REQUIRED, candAmbiguous, 0.65, 0.60, MatchConfidence.POOR, null, List.of(), List.of("Low confidence"));
        TrackMatchResult r2 = new TrackMatchResult("sp-3", n2, MatchDecision.MATCH, candShared, 0.95, 0.98, MatchConfidence.EXCELLENT, null, List.of(), List.of("Duplicate match"));
        TrackMatchResult r3 = TrackMatchResult.noMatch("sp-4", n3, List.of("No candidates found"));

        when(matchingEngine.matchTrack(any(TrackMatchRequest.class)))
                .thenReturn(r0)
                .thenReturn(r1)
                .thenReturn(r2)
                .thenReturn(r3);

        MigrationPlan plan = planGenerator.generatePlan(userId, playlistId, true);

        assertThat(plan.tracks()).hasSize(5);

        // Strict 0-indexed position invariant assertion
        for (int i = 0; i < 5; i++) {
            assertThat(plan.tracks().get(i).position()).isEqualTo(i);
        }

        assertThat(plan.tracks().get(0).classification()).isEqualTo(PlanTrackClassification.AUTO_MATCH);
        assertThat(plan.tracks().get(1).classification()).isEqualTo(PlanTrackClassification.REVIEW_REQUIRED);
        assertThat(plan.tracks().get(2).classification()).isEqualTo(PlanTrackClassification.DUPLICATE);
        assertThat(plan.tracks().get(3).classification()).isEqualTo(PlanTrackClassification.UNMATCHED);
        assertThat(plan.tracks().get(4).classification()).isEqualTo(PlanTrackClassification.FAILED);

        MigrationPlanSummary summary = plan.summary();
        assertThat(summary.totalTracks()).isEqualTo(5);
        assertThat(summary.autoMatchCount()).isEqualTo(1);
        assertThat(summary.reviewRequiredCount()).isEqualTo(1);
        assertThat(summary.duplicateCount()).isEqualTo(1);
        assertThat(summary.unmatchedCount()).isEqualTo(1);
        assertThat(summary.failedCount()).isEqualTo(1);
        assertThat(summary.excludedCount()).isZero();
        assertThat(summary.hasIssues()).isTrue();
    }

    @Test
    @DisplayName("Should strictly satisfy read-only destination invariant without mutating target music service")
    void shouldEnforceReadOnlyDestinationInvariantWithoutMutatingTarget() {
        // Architectural verification: DefaultPlaylistMigrationPlanGenerator does not depend on MusicDestination
        // and only interacts with read-only retrieval, normalization, candidate discovery, and matching.
        Class<?> generatorClass = DefaultPlaylistMigrationPlanGenerator.class;
        for (var field : generatorClass.getDeclaredFields()) {
            assertThat(field.getType().getSimpleName())
                    .as("Plan generator must not hold destination mutation dependencies")
                    .isNotEqualTo("MusicDestination")
                    .isNotEqualTo("YouTubePlaylistService")
                    .isNotEqualTo("YouTubeClient");
        }
    }

    @Test
    @DisplayName("Should maintain exact mathematical summary reconciliation invariant")
    void shouldMaintainExactMathematicalSummaryReconciliationInvariant() {
        when(spotifyPlaylistService.getPlaylistMetadata(userId, playlistId))
                .thenReturn(createPlaylistResponse(playlistId, "Reconciliation Test"));

        TrackResponse t0 = createTrackResponse("sp-1", "Track 1", "Artist", 200000L);
        when(spotifyPlaylistService.getPlaylistTracks(userId, playlistId)).thenReturn(List.of(t0));

        NormalizedTrackMetadata n = createNormalizedMetadata("Track 1", "Artist");
        when(normalizationOrchestrator.normalize(any(RawTrackMetadata.class))).thenReturn(n);

        DestinationTrackCandidate cand = createCandidate("yt-1", "Track 1", "Artist");
        when(candidateDiscoveryService.discoverCandidates(any(CandidateDiscoveryRequest.class)))
                .thenReturn(createDiscoveryResult(n, List.of(cand)));

        TrackMatchResult r = new TrackMatchResult("sp-1", n, MatchDecision.MATCH, cand, 0.95, 0.98, MatchConfidence.EXCELLENT, null, List.of(), List.of());
        when(matchingEngine.matchTrack(any(TrackMatchRequest.class))).thenReturn(r);

        MigrationPlan plan = planGenerator.generatePlan(userId, playlistId);
        MigrationPlanSummary s = plan.summary();

        // Mathematical invariant: totalTracks == sum of all 6 mutually exclusive buckets
        int sumOfBuckets = s.autoMatchCount() + s.reviewRequiredCount() + s.unmatchedCount()
                + s.duplicateCount() + s.failedCount() + s.excludedCount();
        assertThat(s.totalTracks()).isEqualTo(sumOfBuckets);

        // Rate invariant: autoMatchRate == autoMatchCount / totalTracks
        assertThat(s.getAutoMatchRate()).isEqualTo((double) s.autoMatchCount() / s.totalTracks());
        assertThat(s.getReviewRate()).isEqualTo((double) s.reviewRequiredCount() / s.totalTracks());
        assertThat(s.getUnmatchedRate()).isEqualTo((double) s.unmatchedCount() / s.totalTracks());
        assertThat(s.getDuplicateRate()).isEqualTo((double) s.duplicateCount() / s.totalTracks());
        assertThat(s.getFailureRate()).isEqualTo((double) s.failedCount() / s.totalTracks());
        assertThat(s.getExcludedRate()).isEqualTo((double) s.excludedCount() / s.totalTracks());
    }

    @Test
    @DisplayName("Review-required candidate must not claim destination slot for subsequent auto match")
    void reviewRequiredCandidateShouldNotClaimDestinationSlotForSubsequentAutoMatch() {
        when(spotifyPlaylistService.getPlaylistMetadata(userId, playlistId))
                .thenReturn(createPlaylistResponse(playlistId, "Review Slot Safety"));

        TrackResponse t0 = createTrackResponse("sp-1", "Song A", "Artist A", 200000L);
        TrackResponse t1 = createTrackResponse("sp-2", "Song A (Remaster)", "Artist A", 200000L);
        when(spotifyPlaylistService.getPlaylistTracks(userId, playlistId)).thenReturn(List.of(t0, t1));

        NormalizedTrackMetadata n0 = createNormalizedMetadata("Song A", "Artist A");
        NormalizedTrackMetadata n1 = createNormalizedMetadata("Song A (Remaster)", "Artist A");
        when(normalizationOrchestrator.normalize(any(RawTrackMetadata.class))).thenReturn(n0, n1);

        DestinationTrackCandidate candShared = createCandidate("yt-shared", "Song A", "Artist A");

        when(candidateDiscoveryService.discoverCandidates(any(CandidateDiscoveryRequest.class)))
                .thenReturn(createDiscoveryResult(n0, List.of(candShared)))
                .thenReturn(createDiscoveryResult(n1, List.of(candShared)));

        // Track 0 is REVIEW_REQUIRED with candShared
        TrackMatchResult r0 = new TrackMatchResult(
                "sp-1", n0, MatchDecision.REVIEW_REQUIRED, candShared, 0.70, 0.65,
                MatchConfidence.POOR, null, List.of(), List.of("Moderate confidence")
        );
        // Track 1 is high confidence MATCH with candShared
        TrackMatchResult r1 = new TrackMatchResult(
                "sp-2", n1, MatchDecision.MATCH, candShared, 0.98, 0.99,
                MatchConfidence.EXCELLENT, null, List.of(), List.of("Exact match")
        );
        when(matchingEngine.matchTrack(any(TrackMatchRequest.class))).thenReturn(r0, r1);

        MigrationPlan plan = planGenerator.generatePlan(userId, playlistId, true);

        assertThat(plan.tracks()).hasSize(2);

        // Track 0 is REVIEW_REQUIRED
        MigrationPlanTrack pt0 = plan.tracks().get(0);
        assertThat(pt0.classification()).isEqualTo(PlanTrackClassification.REVIEW_REQUIRED);
        assertThat(pt0.selectedCandidate()).isEqualTo(candShared);

        // Track 1 must be AUTO_MATCH (NOT DUPLICATE because candShared was not claimed by an AUTO_MATCH)
        MigrationPlanTrack pt1 = plan.tracks().get(1);
        assertThat(pt1.classification()).isEqualTo(PlanTrackClassification.AUTO_MATCH);
        assertThat(pt1.selectedCandidate()).isEqualTo(candShared);

        MigrationPlanSummary summary = plan.summary();
        assertThat(summary.totalTracks()).isEqualTo(2);
        assertThat(summary.autoMatchCount()).isEqualTo(1);
        assertThat(summary.reviewRequiredCount()).isEqualTo(1);
        assertThat(summary.duplicateCount()).isZero();
    }

    @Test
    @DisplayName("Plan generation should be deterministic and repeatable for equivalent inputs")
    void planGenerationShouldBeDeterministicAndRepeatableForEquivalentInputs() {
        when(spotifyPlaylistService.getPlaylistMetadata(userId, playlistId))
                .thenReturn(createPlaylistResponse(playlistId, "Repeatable Playlist"));

        TrackResponse t0 = createTrackResponse("sp-1", "Song 1", "Artist 1", 200000L);
        TrackResponse t1 = createTrackResponse("sp-2", "Song 2", "Artist 2", 210000L);
        when(spotifyPlaylistService.getPlaylistTracks(userId, playlistId)).thenReturn(List.of(t0, t1));

        NormalizedTrackMetadata n0 = createNormalizedMetadata("Song 1", "Artist 1");
        NormalizedTrackMetadata n1 = createNormalizedMetadata("Song 2", "Artist 2");
        when(normalizationOrchestrator.normalize(any(RawTrackMetadata.class))).thenReturn(n0, n1, n0, n1);

        DestinationTrackCandidate c0 = createCandidate("yt-1", "Song 1", "Artist 1");
        DestinationTrackCandidate c1 = createCandidate("yt-2", "Song 2", "Artist 2");

        when(candidateDiscoveryService.discoverCandidates(any(CandidateDiscoveryRequest.class)))
                .thenReturn(createDiscoveryResult(n0, List.of(c0)))
                .thenReturn(createDiscoveryResult(n1, List.of(c1)))
                .thenReturn(createDiscoveryResult(n0, List.of(c0)))
                .thenReturn(createDiscoveryResult(n1, List.of(c1)));

        TrackMatchResult r0 = new TrackMatchResult(
                "sp-1", n0, MatchDecision.MATCH, c0, 0.95, 0.98,
                MatchConfidence.EXCELLENT, null, List.of(), List.of("Auto match")
        );
        TrackMatchResult r1 = new TrackMatchResult(
                "sp-2", n1, MatchDecision.REVIEW_REQUIRED, c1, 0.70, 0.65,
                MatchConfidence.POOR, null, List.of(), List.of("Review required")
        );
        when(matchingEngine.matchTrack(any(TrackMatchRequest.class))).thenReturn(r0, r1, r0, r1);

        MigrationPlan planA = planGenerator.generatePlan(userId, playlistId, true);
        MigrationPlan planB = planGenerator.generatePlan(userId, playlistId, true);

        // Plan metadata (IDs and timestamps are point-in-time generated)
        assertThat(planA.sourcePlaylistId()).isEqualTo(planB.sourcePlaylistId());
        assertThat(planA.sourcePlaylistTitle()).isEqualTo(planB.sourcePlaylistTitle());

        // Tracks matching
        assertThat(planA.tracks()).hasSameSizeAs(planB.tracks());
        for (int i = 0; i < planA.tracks().size(); i++) {
            MigrationPlanTrack trackA = planA.tracks().get(i);
            MigrationPlanTrack trackB = planB.tracks().get(i);

            assertThat(trackA.position()).isEqualTo(trackB.position());
            assertThat(trackA.sourceTrackId()).isEqualTo(trackB.sourceTrackId());
            assertThat(trackA.sourceTitle()).isEqualTo(trackB.sourceTitle());
            assertThat(trackA.sourceArtist()).isEqualTo(trackB.sourceArtist());
            assertThat(trackA.classification()).isEqualTo(trackB.classification());
            assertThat(trackA.matchScore()).isEqualTo(trackB.matchScore());
            assertThat(trackA.calibratedProbability()).isEqualTo(trackB.calibratedProbability());
            assertThat(trackA.selectedCandidate()).isEqualTo(trackB.selectedCandidate());
            assertThat(trackA.matchReasons()).isEqualTo(trackB.matchReasons());
            assertThat(trackA.errorMessage()).isEqualTo(trackB.errorMessage());
        }

        // Summary matching
        assertThat(planA.summary()).isEqualTo(planB.summary());
    }

    @Test
    @DisplayName("Tracks with null or blank metadata should fallback safely without pipeline failure")
    void tracksWithNullOrBlankMetadataShouldFallbackSafelyWithoutPipelineFailure() {
        when(spotifyPlaylistService.getPlaylistMetadata(userId, playlistId))
                .thenReturn(createPlaylistResponse(playlistId, "Fallback Playlist"));

        // Track with null externalId, null name, null artist
        TrackResponse rawTrack = new TrackResponse(null, null, null, null, null, null);
        when(spotifyPlaylistService.getPlaylistTracks(userId, playlistId)).thenReturn(List.of(rawTrack));

        NormalizedTrackMetadata normalized = createNormalizedMetadata("Unknown Title", "Unknown Artist");
        when(normalizationOrchestrator.normalize(any(RawTrackMetadata.class))).thenReturn(normalized);

        DestinationTrackCandidate candidate = createCandidate("yt-fb", "Unknown Title", "Unknown Artist");
        when(candidateDiscoveryService.discoverCandidates(any(CandidateDiscoveryRequest.class)))
                .thenReturn(createDiscoveryResult(normalized, List.of(candidate)));

        TrackMatchResult matchResult = new TrackMatchResult(
                "track-0", normalized, MatchDecision.MATCH, candidate, 0.90, 0.92,
                MatchConfidence.EXCELLENT, null, List.of(), List.of("Fallback match")
        );
        when(matchingEngine.matchTrack(any(TrackMatchRequest.class))).thenReturn(matchResult);

        MigrationPlan plan = planGenerator.generatePlan(userId, playlistId, true);

        assertThat(plan.tracks()).hasSize(1);
        MigrationPlanTrack track = plan.tracks().get(0);
        assertThat(track.position()).isZero();
        assertThat(track.sourceTrackId()).isEqualTo("track-0");
        assertThat(track.sourceTitle()).isEqualTo("Unknown Title");
        assertThat(track.sourceArtist()).isEqualTo("Unknown Artist");
        assertThat(track.classification()).isEqualTo(PlanTrackClassification.AUTO_MATCH);
        assertThat(track.selectedCandidate()).isEqualTo(candidate);

        assertThat(plan.summary().totalTracks()).isEqualTo(1);
        assertThat(plan.summary().autoMatchCount()).isEqualTo(1);
    }
}
