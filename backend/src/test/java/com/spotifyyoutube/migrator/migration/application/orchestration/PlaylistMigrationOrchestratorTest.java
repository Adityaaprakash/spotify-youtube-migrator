package com.spotifyyoutube.migrator.migration.application.orchestration;

import com.spotifyyoutube.migrator.identity.domain.User;
import com.spotifyyoutube.migrator.matching.discovery.CandidateDiscoveryRequest;
import com.spotifyyoutube.migrator.matching.discovery.CandidateDiscoveryResult;
import com.spotifyyoutube.migrator.matching.discovery.CandidateDiscoveryService;
import com.spotifyyoutube.migrator.matching.discovery.DiscoveredCandidate;
import com.spotifyyoutube.migrator.matching.discovery.SearchQuery;
import com.spotifyyoutube.migrator.matching.discovery.SearchQueryStrategy;
import com.spotifyyoutube.migrator.matching.engine.MatchDecision;
import com.spotifyyoutube.migrator.matching.engine.TrackMatchRequest;
import com.spotifyyoutube.migrator.matching.engine.TrackMatchResult;
import com.spotifyyoutube.migrator.matching.engine.TrackMatchingEngine;
import com.spotifyyoutube.migrator.matching.normalization.NormalizationOrchestrator;
import com.spotifyyoutube.migrator.matching.normalization.NormalizedTrackMetadata;
import com.spotifyyoutube.migrator.matching.normalization.RawTrackMetadata;
import com.spotifyyoutube.migrator.migration.application.destination.MusicDestination;
import com.spotifyyoutube.migrator.migration.application.destination.model.AddTrackToDestinationPlaylistRequest;
import com.spotifyyoutube.migrator.migration.application.destination.model.CreateDestinationPlaylistRequest;
import com.spotifyyoutube.migrator.migration.application.destination.model.DestinationPlaylist;
import com.spotifyyoutube.migrator.migration.application.destination.model.DestinationPlaylistItem;
import com.spotifyyoutube.migrator.migration.application.destination.model.DestinationTrackCandidate;
import com.spotifyyoutube.migrator.migration.domain.MigrationJob;
import com.spotifyyoutube.migrator.migration.domain.MigrationStatus;
import com.spotifyyoutube.migrator.migration.domain.MigrationTask;
import com.spotifyyoutube.migrator.migration.repository.MigrationJobRepository;
import com.spotifyyoutube.migrator.migration.repository.MigrationTaskRepository;
import com.spotifyyoutube.migrator.playlist.api.dto.PlaylistResponse;
import com.spotifyyoutube.migrator.playlist.api.dto.TrackResponse;
import com.spotifyyoutube.migrator.playlist.domain.Platform;
import com.spotifyyoutube.migrator.spotify.application.SpotifyPlaylistService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

import com.spotifyyoutube.migrator.common.exception.InvalidStateException;
import com.spotifyyoutube.migrator.common.exception.ResourceNotFoundException;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("PlaylistMigrationOrchestrator Tests (Phase 8G)")
class PlaylistMigrationOrchestratorTest {

    @Mock
    private SpotifyPlaylistService spotifyPlaylistService;

    @Mock
    private NormalizationOrchestrator normalizationOrchestrator;

    @Mock
    private CandidateDiscoveryService candidateDiscoveryService;

    @Mock
    private TrackMatchingEngine matchingEngine;

    @Mock
    private MusicDestination musicDestination;

    @Mock
    private MigrationJobRepository migrationJobRepository;

    @Mock
    private MigrationTaskRepository migrationTaskRepository;

    private PlaylistMigrationOrchestrator orchestrator;

    private final UUID userId = UUID.randomUUID();
    private final String sourcePlaylistId = "spot-pl-123";

    @BeforeEach
    void setUp() {
        orchestrator = new PlaylistMigrationOrchestrator(
                spotifyPlaylistService,
                normalizationOrchestrator,
                candidateDiscoveryService,
                matchingEngine,
                musicDestination,
                migrationJobRepository,
                migrationTaskRepository
        );
    }

    @Nested
    @DisplayName("End-to-end migration execution")
    class EndToEndExecutionTests {

        @Test
        @DisplayName("should migrate all tracks successfully preserving source order")
        void shouldMigrateSuccessfully() {
            PlaylistResponse plResponse = new PlaylistResponse(
                    UUID.randomUUID(), "Top Hits", "My favorite hits", Platform.SPOTIFY, sourcePlaylistId, "http://spot/123", 2
            );
            when(spotifyPlaylistService.getPlaylistMetadata(userId, sourcePlaylistId)).thenReturn(plResponse);

            DestinationPlaylist destPlaylist = new DestinationPlaylist(
                    "yt-pl-456", "Top Hits", "My favorite hits", "public", "https://youtube.com/playlist?list=yt-pl-456", Platform.YOUTUBE
            );
            when(musicDestination.createPlaylist(eq(userId), any(CreateDestinationPlaylistRequest.class))).thenReturn(destPlaylist);

            TrackResponse track1 = new TrackResponse(UUID.randomUUID(), "Song One", "Artist A", "Album A", 200000, "tr-1");
            TrackResponse track2 = new TrackResponse(UUID.randomUUID(), "Song Two", "Artist B", "Album B", 180000, "tr-2");
            when(spotifyPlaylistService.getPlaylistTracks(userId, sourcePlaylistId)).thenReturn(List.of(track1, track2));

            NormalizedTrackMetadata norm1 = createNormalizedMetadata("Song One", "Artist A");
            NormalizedTrackMetadata norm2 = createNormalizedMetadata("Song Two", "Artist B");
            when(normalizationOrchestrator.normalize(any(RawTrackMetadata.class)))
                    .thenReturn(norm1)
                    .thenReturn(norm2);

            DestinationTrackCandidate cand1 = createCandidate("vid-1", "Artist A - Song One");
            DestinationTrackCandidate cand2 = createCandidate("vid-2", "Artist B - Song Two");

            CandidateDiscoveryResult disc1 = CandidateDiscoveryResult.success(norm1, List.of(new DiscoveredCandidate(cand1, new SearchQuery(cand1.title(), SearchQueryStrategy.PRIMARY_TITLE_ARTIST))), List.of());
            CandidateDiscoveryResult disc2 = CandidateDiscoveryResult.success(norm2, List.of(new DiscoveredCandidate(cand2, new SearchQuery(cand2.title(), SearchQueryStrategy.PRIMARY_TITLE_ARTIST))), List.of());
            when(candidateDiscoveryService.discoverCandidates(any(CandidateDiscoveryRequest.class)))
                    .thenReturn(disc1)
                    .thenReturn(disc2);

            TrackMatchResult match1 = createMatchResult("tr-1", norm1, cand1, MatchDecision.MATCH, 0.95, 0.92, List.of("Exact match"));
            TrackMatchResult match2 = createMatchResult("tr-2", norm2, cand2, MatchDecision.MATCH, 0.88, 0.85, List.of("Artist match"));
            when(matchingEngine.matchTrack(any(TrackMatchRequest.class)))
                    .thenReturn(match1)
                    .thenReturn(match2);

            when(musicDestination.addTrackToPlaylist(eq(userId), eq("yt-pl-456"), any(AddTrackToDestinationPlaylistRequest.class)))
                    .thenReturn(new DestinationPlaylistItem("item-1", "yt-pl-456", "vid-1", 0))
                    .thenReturn(new DestinationPlaylistItem("item-2", "yt-pl-456", "vid-2", 1));

            MigrationExecutionSummary summary = orchestrator.migratePlaylist(userId, sourcePlaylistId, true);

            assertThat(summary).isNotNull();
            assertThat(summary.finalStatus()).isEqualTo(MigrationStatus.COMPLETED);
            assertThat(summary.totalTracks()).isEqualTo(2);
            assertThat(summary.processedTracks()).isEqualTo(2);
            assertThat(summary.failedTracks()).isEqualTo(0);
            assertThat(summary.destinationPlaylistId()).contains("yt-pl-456");
            assertThat(summary.destinationPlaylistUrl()).contains("https://youtube.com/playlist?list=yt-pl-456");
            assertThat(summary.trackOutcomes()).hasSize(2);

            // Verify order preservation
            assertThat(summary.trackOutcomes().get(0).position()).isEqualTo(0);
            assertThat(summary.trackOutcomes().get(0).sourceTrackId()).isEqualTo("tr-1");
            assertThat(summary.trackOutcomes().get(0).destinationTrackId()).contains("vid-1");

            assertThat(summary.trackOutcomes().get(1).position()).isEqualTo(1);
            assertThat(summary.trackOutcomes().get(1).sourceTrackId()).isEqualTo("tr-2");
            assertThat(summary.trackOutcomes().get(1).destinationTrackId()).contains("vid-2");

            // Verify calls to musicDestination
            ArgumentCaptor<AddTrackToDestinationPlaylistRequest> addCaptor = ArgumentCaptor.forClass(AddTrackToDestinationPlaylistRequest.class);
            verify(musicDestination, times(2)).addTrackToPlaylist(eq(userId), eq("yt-pl-456"), addCaptor.capture());
            assertThat(addCaptor.getAllValues().get(0).trackId()).isEqualTo("vid-1");
            assertThat(addCaptor.getAllValues().get(1).trackId()).isEqualTo("vid-2");
        }

        @Test
        @DisplayName("should handle empty source playlist gracefully")
        void shouldHandleEmptyPlaylist() {
            PlaylistResponse plResponse = new PlaylistResponse(
                    UUID.randomUUID(), "Empty Playlist", null, Platform.SPOTIFY, sourcePlaylistId, null, 0
            );
            when(spotifyPlaylistService.getPlaylistMetadata(userId, sourcePlaylistId)).thenReturn(plResponse);
            when(musicDestination.createPlaylist(eq(userId), any())).thenReturn(
                    new DestinationPlaylist("yt-empty", "Empty Playlist", null, "private", null, Platform.YOUTUBE)
            );
            when(spotifyPlaylistService.getPlaylistTracks(userId, sourcePlaylistId)).thenReturn(List.of());

            MigrationExecutionSummary summary = orchestrator.migratePlaylist(userId, sourcePlaylistId, true);

            assertThat(summary.finalStatus()).isEqualTo(MigrationStatus.COMPLETED);
            assertThat(summary.totalTracks()).isEqualTo(0);
            assertThat(summary.processedTracks()).isEqualTo(0);
            assertThat(summary.trackOutcomes()).isEmpty();
            verify(musicDestination, never()).addTrackToPlaylist(any(), any(), any());
        }

        @Test
        @DisplayName("should handle NO_MATCH and REVIEW_REQUIRED preserving partial progress")
        void shouldHandleNoMatchAndReviewRequired() {
            PlaylistResponse plResponse = new PlaylistResponse(
                    UUID.randomUUID(), "Mixed", null, Platform.SPOTIFY, sourcePlaylistId, null, 3
            );
            when(spotifyPlaylistService.getPlaylistMetadata(userId, sourcePlaylistId)).thenReturn(plResponse);
            when(musicDestination.createPlaylist(eq(userId), any())).thenReturn(
                    new DestinationPlaylist("yt-mixed", "Mixed", null, "private", null, Platform.YOUTUBE)
            );

            TrackResponse tr1 = new TrackResponse(UUID.randomUUID(), "Good Song", "Artist 1", "Album 1", 200000, "tr-1");
            TrackResponse tr2 = new TrackResponse(UUID.randomUUID(), "Ambiguous Song", "Artist 2", "Album 2", 190000, "tr-2");
            TrackResponse tr3 = new TrackResponse(UUID.randomUUID(), "Obscure Song", "Artist 3", "Album 3", 210000, "tr-3");
            when(spotifyPlaylistService.getPlaylistTracks(userId, sourcePlaylistId)).thenReturn(List.of(tr1, tr2, tr3));

            NormalizedTrackMetadata norm1 = createNormalizedMetadata("Good Song", "Artist 1");
            NormalizedTrackMetadata norm2 = createNormalizedMetadata("Ambiguous Song", "Artist 2");
            NormalizedTrackMetadata norm3 = createNormalizedMetadata("Obscure Song", "Artist 3");
            when(normalizationOrchestrator.normalize(any(RawTrackMetadata.class)))
                    .thenReturn(norm1)
                    .thenReturn(norm2)
                    .thenReturn(norm3);

            DestinationTrackCandidate cand1 = createCandidate("vid-1", "Good Song");
            DestinationTrackCandidate cand2 = createCandidate("vid-2", "Ambiguous Cover");

            when(candidateDiscoveryService.discoverCandidates(any(CandidateDiscoveryRequest.class)))
                    .thenReturn(CandidateDiscoveryResult.success(norm1, List.of(new DiscoveredCandidate(cand1, new SearchQuery(cand1.title(), SearchQueryStrategy.PRIMARY_TITLE_ARTIST))), List.of()))
                    .thenReturn(CandidateDiscoveryResult.success(norm2, List.of(new DiscoveredCandidate(cand2, new SearchQuery(cand2.title(), SearchQueryStrategy.PRIMARY_TITLE_ARTIST))), List.of()))
                    .thenReturn(CandidateDiscoveryResult.empty(norm3, List.of()));

            // tr1: MATCH, tr2: REVIEW_REQUIRED, tr3: NO_MATCH
            when(matchingEngine.matchTrack(any(TrackMatchRequest.class)))
                    .thenReturn(createMatchResult("tr-1", norm1, cand1, MatchDecision.MATCH, 0.95, 0.92, List.of("OK")))
                    .thenReturn(createMatchResult("tr-2", norm2, cand2, MatchDecision.REVIEW_REQUIRED, 0.72, null, List.of("Version penalty: cover")))
                    .thenReturn(TrackMatchResult.noMatch("tr-3", norm3, List.of("No candidates")));

            MigrationExecutionSummary summary = orchestrator.migratePlaylist(userId, sourcePlaylistId, true);

            assertThat(summary.finalStatus()).isEqualTo(MigrationStatus.PARTIALLY_COMPLETED);
            assertThat(summary.totalTracks()).isEqualTo(3);
            assertThat(summary.processedTracks()).isEqualTo(1);
            assertThat(summary.failedTracks()).isEqualTo(2);
            assertThat(summary.reviewRequiredTracks()).isEqualTo(1);

            // Only 1 track added to destination
            verify(musicDestination, times(1)).addTrackToPlaylist(any(), any(), any());
        }

        @Test
        @DisplayName("should prevent duplicate destination track additions when deduplication enabled")
        void shouldPreventDuplicates() {
            PlaylistResponse plResponse = new PlaylistResponse(
                    UUID.randomUUID(), "Dups", null, Platform.SPOTIFY, sourcePlaylistId, null, 2
            );
            when(spotifyPlaylistService.getPlaylistMetadata(userId, sourcePlaylistId)).thenReturn(plResponse);
            when(musicDestination.createPlaylist(eq(userId), any())).thenReturn(
                    new DestinationPlaylist("yt-dup", "Dups", null, "private", null, Platform.YOUTUBE)
            );

            TrackResponse tr1 = new TrackResponse(UUID.randomUUID(), "Song", "Artist", "Album", 200000, "tr-1");
            TrackResponse tr2 = new TrackResponse(UUID.randomUUID(), "Song (Radio Edit)", "Artist", "Album", 200000, "tr-2");
            when(spotifyPlaylistService.getPlaylistTracks(userId, sourcePlaylistId)).thenReturn(List.of(tr1, tr2));

            NormalizedTrackMetadata norm = createNormalizedMetadata("Song", "Artist");
            when(normalizationOrchestrator.normalize(any(RawTrackMetadata.class))).thenReturn(norm);

            // Both match to same YouTube video vid-1
            DestinationTrackCandidate sameCand = createCandidate("vid-1", "Song - Artist");
            when(candidateDiscoveryService.discoverCandidates(any(CandidateDiscoveryRequest.class)))
                    .thenReturn(CandidateDiscoveryResult.success(norm, List.of(new DiscoveredCandidate(sameCand, new SearchQuery(sameCand.title(), SearchQueryStrategy.PRIMARY_TITLE_ARTIST))), List.of()));

            when(matchingEngine.matchTrack(any(TrackMatchRequest.class)))
                    .thenReturn(createMatchResult("tr-1", norm, sameCand, MatchDecision.MATCH, 0.95, 0.92, List.of("OK")))
                    .thenReturn(createMatchResult("tr-2", norm, sameCand, MatchDecision.MATCH, 0.90, 0.88, List.of("OK")));

            MigrationExecutionSummary summary = orchestrator.migratePlaylist(userId, sourcePlaylistId, true);

            assertThat(summary.totalTracks()).isEqualTo(2);
            assertThat(summary.processedTracks()).isEqualTo(1);
            assertThat(summary.duplicateTracks()).isEqualTo(1);
            // musicDestination should be called only once
            verify(musicDestination, times(1)).addTrackToPlaylist(eq(userId), eq("yt-dup"), any());
        }

        @Test
        @ExtendWith(OutputCaptureExtension.class)
        @DisplayName("should sanitize secrets and tokens from error messages and logs")
        void shouldSanitizeTokensFromErrors(CapturedOutput output) {
            PlaylistResponse plResponse = new PlaylistResponse(
                    UUID.randomUUID(), "Error PL", null, Platform.SPOTIFY, sourcePlaylistId, null, 1
            );
            when(spotifyPlaylistService.getPlaylistMetadata(userId, sourcePlaylistId)).thenReturn(plResponse);
            when(musicDestination.createPlaylist(eq(userId), any())).thenReturn(
                    new DestinationPlaylist("yt-err", "Error PL", null, "private", null, Platform.YOUTUBE)
            );

            TrackResponse tr1 = new TrackResponse(UUID.randomUUID(), "Failing Track", "Artist", "Album", 200000, "tr-1");
            when(spotifyPlaylistService.getPlaylistTracks(userId, sourcePlaylistId)).thenReturn(List.of(tr1));

            when(normalizationOrchestrator.normalize(any())).thenThrow(
                    new RuntimeException("API failed with Bearer secret-oauth-token-12345 in call")
            );

            MigrationExecutionSummary summary = orchestrator.migratePlaylist(userId, sourcePlaylistId, true);

            assertThat(summary.finalStatus()).isEqualTo(MigrationStatus.FAILED);
            assertThat(summary.failedTracks()).isEqualTo(1);
            assertThat(summary.trackOutcomes().getFirst().getMessage().orElse(""))
                    .doesNotContain("secret-oauth-token-12345")
                    .contains("[REDACTED]");

            // Verify logged output is sanitized and does not leak synthetic bearer token
            assertThat(output.getAll())
                    .doesNotContain("secret-oauth-token-12345")
                    .contains("Bearer [REDACTED]");
        }

        @Test
        @DisplayName("should preserve partial progress when a single track throws an unexpected exception")
        void shouldPreservePartialProgressWhenSingleTrackFailsWithException() {
            PlaylistResponse plResponse = new PlaylistResponse(
                    UUID.randomUUID(), "Partial PL", null, Platform.SPOTIFY, sourcePlaylistId, null, 2
            );
            when(spotifyPlaylistService.getPlaylistMetadata(userId, sourcePlaylistId)).thenReturn(plResponse);
            when(musicDestination.createPlaylist(eq(userId), any())).thenReturn(
                    new DestinationPlaylist("yt-part", "Partial PL", null, "private", null, Platform.YOUTUBE)
            );

            TrackResponse tr1 = new TrackResponse(UUID.randomUUID(), "Crashing Track", "Artist 1", "Album 1", 200000, "tr-1");
            TrackResponse tr2 = new TrackResponse(UUID.randomUUID(), "Good Track", "Artist 2", "Album 2", 180000, "tr-2");
            when(spotifyPlaylistService.getPlaylistTracks(userId, sourcePlaylistId)).thenReturn(List.of(tr1, tr2));

            // Track 1 fails during normalization
            when(normalizationOrchestrator.normalize(any(RawTrackMetadata.class)))
                    .thenThrow(new RuntimeException("Transient normalization parser error"))
                    .thenReturn(createNormalizedMetadata("Good Track", "Artist 2"));

            DestinationTrackCandidate cand2 = createCandidate("vid-2", "Artist 2 - Good Track");
            NormalizedTrackMetadata norm2 = createNormalizedMetadata("Good Track", "Artist 2");
            CandidateDiscoveryResult disc2 = CandidateDiscoveryResult.success(
                    norm2, List.of(new DiscoveredCandidate(cand2, new SearchQuery(cand2.title(), SearchQueryStrategy.PRIMARY_TITLE_ARTIST))), List.of());
            when(candidateDiscoveryService.discoverCandidates(any(CandidateDiscoveryRequest.class)))
                    .thenReturn(disc2);

            when(matchingEngine.matchTrack(any(TrackMatchRequest.class)))
                    .thenReturn(createMatchResult("tr-2", norm2, cand2, MatchDecision.MATCH, 0.92, 0.90, List.of("Matched")));

            when(musicDestination.addTrackToPlaylist(eq(userId), eq("yt-part"), any(AddTrackToDestinationPlaylistRequest.class)))
                    .thenReturn(new DestinationPlaylistItem("item-2", "yt-part", "vid-2", 0));

            MigrationExecutionSummary summary = orchestrator.migratePlaylist(userId, sourcePlaylistId, true);

            assertThat(summary.finalStatus()).isEqualTo(MigrationStatus.PARTIALLY_COMPLETED);
            assertThat(summary.totalTracks()).isEqualTo(2);
            assertThat(summary.processedTracks()).isEqualTo(1);
            assertThat(summary.failedTracks()).isEqualTo(1);
            assertThat(summary.trackOutcomes()).hasSize(2);
            assertThat(summary.trackOutcomes().get(0).status()).isEqualTo(MigrationStatus.FAILED);
            assertThat(summary.trackOutcomes().get(0).getMessage().orElse("")).contains("Track processing failure");
            assertThat(summary.trackOutcomes().get(1).status()).isEqualTo(MigrationStatus.COMPLETED);

            // Verified destination track added only for track 2
            verify(musicDestination, times(1)).addTrackToPlaylist(eq(userId), eq("yt-part"), any());
        }

        @Test
        @DisplayName("should preserve sequential destination insertion ordering when intermediate tracks fail or review")
        void shouldPreserveDestinationInsertionOrderWhenIntermediateTracksFailOrReview() {
            PlaylistResponse plResponse = new PlaylistResponse(
                    UUID.randomUUID(), "Order PL", null, Platform.SPOTIFY, sourcePlaylistId, null, 4
            );
            when(spotifyPlaylistService.getPlaylistMetadata(userId, sourcePlaylistId)).thenReturn(plResponse);
            when(musicDestination.createPlaylist(eq(userId), any())).thenReturn(
                    new DestinationPlaylist("yt-order", "Order PL", null, "private", null, Platform.YOUTUBE)
            );

            TrackResponse tr0 = new TrackResponse(UUID.randomUUID(), "Track 0", "Artist", "Album", 200000, "tr-0");
            TrackResponse tr1 = new TrackResponse(UUID.randomUUID(), "Track 1", "Artist", "Album", 200000, "tr-1");
            TrackResponse tr2 = new TrackResponse(UUID.randomUUID(), "Track 2", "Artist", "Album", 200000, "tr-2");
            TrackResponse tr3 = new TrackResponse(UUID.randomUUID(), "Track 3", "Artist", "Album", 200000, "tr-3");
            when(spotifyPlaylistService.getPlaylistTracks(userId, sourcePlaylistId)).thenReturn(List.of(tr0, tr1, tr2, tr3));

            NormalizedTrackMetadata norm0 = createNormalizedMetadata("Track 0", "Artist");
            NormalizedTrackMetadata norm2 = createNormalizedMetadata("Track 2", "Artist");
            NormalizedTrackMetadata norm3 = createNormalizedMetadata("Track 3", "Artist");

            // Track 0 matches -> vid-0
            // Track 1 throws runtime exception
            // Track 2 produces REVIEW_REQUIRED
            // Track 3 matches -> vid-3
            when(normalizationOrchestrator.normalize(any(RawTrackMetadata.class)))
                    .thenReturn(norm0)
                    .thenThrow(new RuntimeException("Simulated normalization failure"))
                    .thenReturn(norm2)
                    .thenReturn(norm3);

            DestinationTrackCandidate cand0 = createCandidate("vid-0", "Track 0");
            DestinationTrackCandidate cand2 = createCandidate("vid-2", "Track 2");
            DestinationTrackCandidate cand3 = createCandidate("vid-3", "Track 3");

            when(candidateDiscoveryService.discoverCandidates(any(CandidateDiscoveryRequest.class)))
                    .thenReturn(CandidateDiscoveryResult.success(norm0, List.of(new DiscoveredCandidate(cand0, new SearchQuery("Track 0", SearchQueryStrategy.PRIMARY_TITLE_ARTIST))), List.of()))
                    .thenReturn(CandidateDiscoveryResult.success(norm2, List.of(new DiscoveredCandidate(cand2, new SearchQuery("Track 2", SearchQueryStrategy.PRIMARY_TITLE_ARTIST))), List.of()))
                    .thenReturn(CandidateDiscoveryResult.success(norm3, List.of(new DiscoveredCandidate(cand3, new SearchQuery("Track 3", SearchQueryStrategy.PRIMARY_TITLE_ARTIST))), List.of()));

            when(matchingEngine.matchTrack(any(TrackMatchRequest.class)))
                    .thenReturn(createMatchResult("tr-0", norm0, cand0, MatchDecision.MATCH, 0.95, 0.90, List.of("OK")))
                    .thenReturn(createMatchResult("tr-2", norm2, cand2, MatchDecision.REVIEW_REQUIRED, 0.60, 0.55, List.of("Ambiguous")))
                    .thenReturn(createMatchResult("tr-3", norm3, cand3, MatchDecision.MATCH, 0.92, 0.88, List.of("OK")));

            MigrationExecutionSummary summary = orchestrator.migratePlaylist(userId, sourcePlaylistId, true);

            assertThat(summary.totalTracks()).isEqualTo(4);
            assertThat(summary.processedTracks()).isEqualTo(2);
            assertThat(summary.failedTracks()).isEqualTo(2);
            assertThat(summary.reviewRequiredTracks()).isEqualTo(1);
            assertThat(summary.finalStatus()).isEqualTo(MigrationStatus.PARTIALLY_COMPLETED);

            // Verify insertion calls: Track 0 at index 0, Track 3 at index 1 (strictly sequential)
            ArgumentCaptor<AddTrackToDestinationPlaylistRequest> addCaptor = ArgumentCaptor.forClass(AddTrackToDestinationPlaylistRequest.class);
            verify(musicDestination, times(2)).addTrackToPlaylist(eq(userId), eq("yt-order"), addCaptor.capture());

            List<AddTrackToDestinationPlaylistRequest> addRequests = addCaptor.getAllValues();
            assertThat(addRequests.get(0).trackId()).isEqualTo("vid-0");
            assertThat(addRequests.get(0).position()).isEqualTo(0);
            assertThat(addRequests.get(1).trackId()).isEqualTo("vid-3");
            assertThat(addRequests.get(1).position()).isEqualTo(1);
        }

        @Test
        @DisplayName("should allow duplicate destination tracks when deduplication is disabled")
        void shouldAllowDuplicateTracksWhenDeduplicationDisabled() {
            PlaylistResponse plResponse = new PlaylistResponse(
                    UUID.randomUUID(), "No Dup Filter PL", null, Platform.SPOTIFY, sourcePlaylistId, null, 2
            );
            when(spotifyPlaylistService.getPlaylistMetadata(userId, sourcePlaylistId)).thenReturn(plResponse);
            when(musicDestination.createPlaylist(eq(userId), any())).thenReturn(
                    new DestinationPlaylist("yt-nodup", "No Dup Filter PL", null, "private", null, Platform.YOUTUBE)
            );

            TrackResponse tr1 = new TrackResponse(UUID.randomUUID(), "Song", "Artist", "Album", 200000, "tr-1");
            TrackResponse tr2 = new TrackResponse(UUID.randomUUID(), "Song (Radio)", "Artist", "Album", 200000, "tr-2");
            when(spotifyPlaylistService.getPlaylistTracks(userId, sourcePlaylistId)).thenReturn(List.of(tr1, tr2));

            NormalizedTrackMetadata norm = createNormalizedMetadata("Song", "Artist");
            when(normalizationOrchestrator.normalize(any(RawTrackMetadata.class))).thenReturn(norm);

            DestinationTrackCandidate sameCand = createCandidate("vid-same", "Song - Artist");
            when(candidateDiscoveryService.discoverCandidates(any(CandidateDiscoveryRequest.class)))
                    .thenReturn(CandidateDiscoveryResult.success(norm, List.of(new DiscoveredCandidate(sameCand, new SearchQuery("Song", SearchQueryStrategy.PRIMARY_TITLE_ARTIST))), List.of()));

            when(matchingEngine.matchTrack(any(TrackMatchRequest.class)))
                    .thenReturn(createMatchResult("tr-1", norm, sameCand, MatchDecision.MATCH, 0.95, 0.90, List.of("OK")))
                    .thenReturn(createMatchResult("tr-2", norm, sameCand, MatchDecision.MATCH, 0.92, 0.88, List.of("OK")));

            // deduplicate = false
            MigrationExecutionSummary summary = orchestrator.migratePlaylist(userId, sourcePlaylistId, false);

            assertThat(summary.totalTracks()).isEqualTo(2);
            assertThat(summary.processedTracks()).isEqualTo(2);
            assertThat(summary.duplicateTracks()).isEqualTo(0);
            assertThat(summary.finalStatus()).isEqualTo(MigrationStatus.COMPLETED);

            // Both tracks added to destination at positions 0 and 1
            ArgumentCaptor<AddTrackToDestinationPlaylistRequest> addCaptor = ArgumentCaptor.forClass(AddTrackToDestinationPlaylistRequest.class);
            verify(musicDestination, times(2)).addTrackToPlaylist(eq(userId), eq("yt-nodup"), addCaptor.capture());
            assertThat(addCaptor.getAllValues().get(0).position()).isEqualTo(0);
            assertThat(addCaptor.getAllValues().get(1).position()).isEqualTo(1);
        }
    }

    @Nested
    @DisplayName("Database MigrationJob orchestration")
    class JobOrchestrationTests {

        @Test
        @DisplayName("should execute migration and update MigrationJob entity in database")
        void shouldExecuteJobEntity() {
            UUID jobId = UUID.randomUUID();
            User user = new User("tester@example.com");
            user.setId(userId);

            MigrationJob job = new MigrationJob();
            job.setId(jobId);
            job.setUser(user);
            job.setSourcePlaylistId(sourcePlaylistId);
            job.setSourcePlatform(Platform.SPOTIFY);
            job.setTargetPlatform(Platform.YOUTUBE);
            job.setStatus(MigrationStatus.PENDING);

            when(migrationJobRepository.findById(jobId)).thenReturn(Optional.of(job));
            when(migrationJobRepository.save(any(MigrationJob.class))).thenAnswer(i -> i.getArguments()[0]);
            when(migrationTaskRepository.save(any(MigrationTask.class))).thenAnswer(i -> i.getArguments()[0]);

            PlaylistResponse plResponse = new PlaylistResponse(
                    UUID.randomUUID(), "Job PL", null, Platform.SPOTIFY, sourcePlaylistId, null, 1
            );
            when(spotifyPlaylistService.getPlaylistMetadata(userId, sourcePlaylistId)).thenReturn(plResponse);
            when(musicDestination.createPlaylist(eq(userId), any())).thenReturn(
                    new DestinationPlaylist("yt-job", "Job PL", null, "private", "https://youtube.com/playlist?list=yt-job", Platform.YOUTUBE)
            );

            TrackResponse tr1 = new TrackResponse(UUID.randomUUID(), "Hit", "Artist", "Album", 200000, "tr-1");
            when(spotifyPlaylistService.getPlaylistTracks(userId, sourcePlaylistId)).thenReturn(List.of(tr1));

            NormalizedTrackMetadata norm = createNormalizedMetadata("Hit", "Artist");
            when(normalizationOrchestrator.normalize(any(RawTrackMetadata.class))).thenReturn(norm);
            DestinationTrackCandidate cand = createCandidate("vid-1", "Hit - Artist");
            when(candidateDiscoveryService.discoverCandidates(any(CandidateDiscoveryRequest.class)))
                    .thenReturn(CandidateDiscoveryResult.success(norm, List.of(new DiscoveredCandidate(cand, new SearchQuery(cand.title(), SearchQueryStrategy.PRIMARY_TITLE_ARTIST))), List.of()));
            when(matchingEngine.matchTrack(any(TrackMatchRequest.class)))
                    .thenReturn(createMatchResult("tr-1", norm, cand, MatchDecision.MATCH, 0.95, 0.90, List.of("OK")));

            MigrationJob completed = orchestrator.executeMigration(jobId);

            assertThat(completed).isNotNull();
            assertThat(completed.getStatus()).isEqualTo(MigrationStatus.COMPLETED);
            assertThat(completed.getTotalTracks()).isEqualTo(1);
            assertThat(completed.getProcessedTracks()).isEqualTo(1);
            assertThat(completed.getFailedTracks()).isEqualTo(0);
            assertThat(completed.getTargetPlaylistUrl()).isEqualTo("https://youtube.com/playlist?list=yt-job");
            assertThat(completed.getCompletedAt()).isNotNull();

            verify(migrationTaskRepository, times(2)).save(any(MigrationTask.class));
        }

        @Test
        @DisplayName("should throw ResourceNotFoundException when job does not exist")
        void shouldThrowResourceNotFoundWhenJobDoesNotExist() {
            UUID unknownJobId = UUID.randomUUID();
            when(migrationJobRepository.findById(unknownJobId)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> orchestrator.executeMigration(unknownJobId))
                    .isInstanceOf(ResourceNotFoundException.class)
                    .hasMessageContaining("Migration job not found with id: " + unknownJobId);
        }

        @Test
        @DisplayName("should throw InvalidStateException when job has already completed")
        void shouldThrowInvalidStateExceptionWhenJobAlreadyCompleted() {
            UUID jobId = UUID.randomUUID();
            MigrationJob completedJob = new MigrationJob();
            completedJob.setId(jobId);
            completedJob.setStatus(MigrationStatus.COMPLETED);

            when(migrationJobRepository.findById(jobId)).thenReturn(Optional.of(completedJob));

            assertThatThrownBy(() -> orchestrator.executeMigration(jobId))
                    .isInstanceOf(InvalidStateException.class)
                    .hasMessageContaining("Migration job has already completed");
        }

        @Test
        @DisplayName("should throw InvalidStateException when platforms are unsupported")
        void shouldThrowInvalidStateExceptionWhenPlatformsAreUnsupported() {
            UUID jobId = UUID.randomUUID();
            MigrationJob badSourceJob = new MigrationJob();
            badSourceJob.setId(jobId);
            badSourceJob.setSourcePlatform(Platform.YOUTUBE);
            badSourceJob.setTargetPlatform(Platform.YOUTUBE);
            badSourceJob.setStatus(MigrationStatus.PENDING);

            when(migrationJobRepository.findById(jobId)).thenReturn(Optional.of(badSourceJob));

            assertThatThrownBy(() -> orchestrator.executeMigration(jobId))
                    .isInstanceOf(InvalidStateException.class)
                    .hasMessageContaining("Unsupported source platform");

            badSourceJob.setSourcePlatform(Platform.SPOTIFY);
            badSourceJob.setTargetPlatform(Platform.SPOTIFY);

            assertThatThrownBy(() -> orchestrator.executeMigration(jobId))
                    .isInstanceOf(InvalidStateException.class)
                    .hasMessageContaining("Unsupported target platform");
        }

        @Test
        @ExtendWith(OutputCaptureExtension.class)
        @DisplayName("should sanitize persisted failure message and logs when migration task fails")
        void shouldSanitizePersistedFailureMessageAndLogs(CapturedOutput output) {
            UUID jobId = UUID.randomUUID();
            User user = new User("tester@example.com");
            user.setId(userId);

            MigrationJob job = new MigrationJob();
            job.setId(jobId);
            job.setUser(user);
            job.setSourcePlaylistId(sourcePlaylistId);
            job.setSourcePlatform(Platform.SPOTIFY);
            job.setTargetPlatform(Platform.YOUTUBE);
            job.setStatus(MigrationStatus.PENDING);

            when(migrationJobRepository.findById(jobId)).thenReturn(Optional.of(job));
            when(migrationJobRepository.save(any(MigrationJob.class))).thenAnswer(i -> i.getArguments()[0]);
            when(migrationTaskRepository.save(any(MigrationTask.class))).thenAnswer(i -> i.getArguments()[0]);

            PlaylistResponse plResponse = new PlaylistResponse(
                    UUID.randomUUID(), "Error PL", null, Platform.SPOTIFY, sourcePlaylistId, null, 1
            );
            when(spotifyPlaylistService.getPlaylistMetadata(userId, sourcePlaylistId)).thenReturn(plResponse);
            when(musicDestination.createPlaylist(eq(userId), any())).thenReturn(
                    new DestinationPlaylist("yt-err", "Error PL", null, "private", "https://youtube.com/playlist?list=yt-err", Platform.YOUTUBE)
            );

            TrackResponse tr1 = new TrackResponse(UUID.randomUUID(), "Failing Track", "Artist", "Album", 200000, "tr-1");
            when(spotifyPlaylistService.getPlaylistTracks(userId, sourcePlaylistId)).thenReturn(List.of(tr1));

            when(normalizationOrchestrator.normalize(any())).thenThrow(
                    new RuntimeException("API failed with Bearer secret-oauth-token-12345 in call")
            );

            MigrationJob completed = orchestrator.executeMigration(jobId);

            assertThat(completed).isNotNull();
            assertThat(completed.getStatus()).isEqualTo(MigrationStatus.FAILED);
            assertThat(completed.getFailedTracks()).isEqualTo(1);

            // Assert logged output does not leak token
            assertThat(output.getAll())
                    .doesNotContain("secret-oauth-token-12345")
                    .contains("Bearer [REDACTED]");

            // Assert persisted failure message in MigrationTask is sanitized
            ArgumentCaptor<MigrationTask> taskCaptor = ArgumentCaptor.forClass(MigrationTask.class);
            verify(migrationTaskRepository, times(2)).save(taskCaptor.capture());
            MigrationTask failedTask = taskCaptor.getAllValues().get(1);
            assertThat(failedTask.getStatus()).isEqualTo(MigrationStatus.FAILED);
            assertThat(failedTask.getErrorMessage())
                    .doesNotContain("secret-oauth-token-12345")
                    .contains("[REDACTED]");
        }

        @Test
        @ExtendWith(OutputCaptureExtension.class)
        @DisplayName("should fail job and persist FAILED terminal status when source playlist metadata fails")
        void shouldFailJobWhenSourcePlaylistMetadataFails(CapturedOutput output) {
            UUID jobId = UUID.randomUUID();
            User user = new User("tester@example.com");
            user.setId(userId);

            MigrationJob job = new MigrationJob();
            job.setId(jobId);
            job.setUser(user);
            job.setSourcePlaylistId(sourcePlaylistId);
            job.setSourcePlatform(Platform.SPOTIFY);
            job.setTargetPlatform(Platform.YOUTUBE);
            job.setStatus(MigrationStatus.PENDING);

            when(migrationJobRepository.findById(jobId)).thenReturn(Optional.of(job));
            when(migrationJobRepository.save(any(MigrationJob.class))).thenAnswer(i -> i.getArguments()[0]);

            when(spotifyPlaylistService.getPlaylistMetadata(userId, sourcePlaylistId))
                    .thenThrow(new RuntimeException("Spotify API error with Bearer secret-oauth-11111 in response"));

            assertThatThrownBy(() -> orchestrator.executeMigration(jobId))
                    .isInstanceOf(RuntimeException.class)
                    .hasMessageContaining("Playlist migration failed")
                    .hasMessageContaining("Bearer [REDACTED]")
                    .hasMessageNotContaining("secret-oauth-11111");

            assertThat(output.getAll())
                    .doesNotContain("secret-oauth-11111")
                    .contains("Bearer [REDACTED]");

            assertThat(job.getStatus()).isEqualTo(MigrationStatus.FAILED);
            assertThat(job.getCompletedAt()).isNotNull();
            assertThat(job.getStartedAt()).isNotNull();
        }

        @Test
        @ExtendWith(OutputCaptureExtension.class)
        @DisplayName("should fail job and persist FAILED terminal status when destination creation fails")
        void shouldFailJobWhenDestinationCreationFails(CapturedOutput output) {
            UUID jobId = UUID.randomUUID();
            User user = new User("tester@example.com");
            user.setId(userId);

            MigrationJob job = new MigrationJob();
            job.setId(jobId);
            job.setUser(user);
            job.setSourcePlaylistId(sourcePlaylistId);
            job.setSourcePlatform(Platform.SPOTIFY);
            job.setTargetPlatform(Platform.YOUTUBE);
            job.setStatus(MigrationStatus.PENDING);

            when(migrationJobRepository.findById(jobId)).thenReturn(Optional.of(job));
            when(migrationJobRepository.save(any(MigrationJob.class))).thenAnswer(i -> i.getArguments()[0]);

            PlaylistResponse plResponse = new PlaylistResponse(
                    UUID.randomUUID(), "Job PL", null, Platform.SPOTIFY, sourcePlaylistId, null, 1
            );
            when(spotifyPlaylistService.getPlaylistMetadata(userId, sourcePlaylistId)).thenReturn(plResponse);
            when(musicDestination.createPlaylist(eq(userId), any()))
                    .thenThrow(new RuntimeException("YouTube API quota exceeded with key=secret-api-key-22222"));

            assertThatThrownBy(() -> orchestrator.executeMigration(jobId))
                    .isInstanceOf(RuntimeException.class)
                    .hasMessageContaining("Playlist migration failed")
                    .hasMessageContaining("key=[REDACTED]")
                    .hasMessageNotContaining("secret-api-key-22222");

            assertThat(output.getAll())
                    .doesNotContain("secret-api-key-22222")
                    .contains("key=[REDACTED]");

            assertThat(job.getStatus()).isEqualTo(MigrationStatus.FAILED);
            assertThat(job.getCompletedAt()).isNotNull();
        }

        @Test
        @ExtendWith(OutputCaptureExtension.class)
        @DisplayName("should fail job and persist FAILED terminal status when source track retrieval fails")
        void shouldFailJobWhenSourceTrackRetrievalFails(CapturedOutput output) {
            UUID jobId = UUID.randomUUID();
            User user = new User("tester@example.com");
            user.setId(userId);

            MigrationJob job = new MigrationJob();
            job.setId(jobId);
            job.setUser(user);
            job.setSourcePlaylistId(sourcePlaylistId);
            job.setSourcePlatform(Platform.SPOTIFY);
            job.setTargetPlatform(Platform.YOUTUBE);
            job.setStatus(MigrationStatus.PENDING);

            when(migrationJobRepository.findById(jobId)).thenReturn(Optional.of(job));
            when(migrationJobRepository.save(any(MigrationJob.class))).thenAnswer(i -> i.getArguments()[0]);

            PlaylistResponse plResponse = new PlaylistResponse(
                    UUID.randomUUID(), "Job PL", null, Platform.SPOTIFY, sourcePlaylistId, null, 1
            );
            when(spotifyPlaylistService.getPlaylistMetadata(userId, sourcePlaylistId)).thenReturn(plResponse);
            when(musicDestination.createPlaylist(eq(userId), any())).thenReturn(
                    new DestinationPlaylist("yt-job", "Job PL", null, "private", null, Platform.YOUTUBE)
            );
            when(spotifyPlaylistService.getPlaylistTracks(userId, sourcePlaylistId))
                    .thenThrow(new RuntimeException("Failed to fetch tracks: access_token=secret-token-33333 expired"));

            assertThatThrownBy(() -> orchestrator.executeMigration(jobId))
                    .isInstanceOf(RuntimeException.class)
                    .hasMessageContaining("Playlist migration failed")
                    .hasMessageContaining("access_token=[REDACTED]")
                    .hasMessageNotContaining("secret-token-33333");

            assertThat(output.getAll())
                    .doesNotContain("secret-token-33333")
                    .contains("access_token=[REDACTED]");

            assertThat(job.getStatus()).isEqualTo(MigrationStatus.FAILED);
            assertThat(job.getCompletedAt()).isNotNull();
        }

        @Test
        @DisplayName("should not mark already completed job as failed or modify its state")
        void shouldNotMarkAlreadyCompletedJobAsFailed() {
            UUID jobId = UUID.randomUUID();
            MigrationJob completedJob = new MigrationJob();
            completedJob.setId(jobId);
            completedJob.setStatus(MigrationStatus.COMPLETED);

            when(migrationJobRepository.findById(jobId)).thenReturn(Optional.of(completedJob));

            assertThatThrownBy(() -> orchestrator.executeMigration(jobId))
                    .isInstanceOf(InvalidStateException.class)
                    .hasMessageContaining("Migration job has already completed");

            verify(migrationJobRepository, never()).save(any());
            assertThat(completedJob.getStatus()).isEqualTo(MigrationStatus.COMPLETED);
        }
    }

    @Nested
    @DisplayName("Credential and Token Sanitization Tests")
    class SanitizationTests {

        @Test
        @DisplayName("should sanitize various realistic credential formats")
        void shouldSanitizeCredentialFormats() {
            // Bearer token
            assertThat(PlaylistMigrationOrchestrator.sanitize("Failed: Bearer secret-token-12345 in header"))
                    .isEqualTo("Failed: Bearer [REDACTED] in header");

            // access_token
            assertThat(PlaylistMigrationOrchestrator.sanitize("Error with access_token=ya29.a0AfH6SMB_secret&scope=read"))
                    .isEqualTo("Error with access_token=[REDACTED]&scope=read");

            // refresh_token
            assertThat(PlaylistMigrationOrchestrator.sanitize("Invalid refresh_token=1//0gXYZ_secret123 in payload"))
                    .isEqualTo("Invalid refresh_token=[REDACTED] in payload");

            // api_key and key
            assertThat(PlaylistMigrationOrchestrator.sanitize("Denied with api_key=AIzaSyD-12345_key in query"))
                    .isEqualTo("Denied with api_key=[REDACTED] in query");
            assertThat(PlaylistMigrationOrchestrator.sanitize("Call failed: key=AIzaSyD-key999, status=403"))
                    .isEqualTo("Call failed: key=[REDACTED], status=403");

            // client_secret and secret
            assertThat(PlaylistMigrationOrchestrator.sanitize("Auth client_secret=GOCSPX-abc123xyz rejected"))
                    .isEqualTo("Auth client_secret=[REDACTED] rejected");
            assertThat(PlaylistMigrationOrchestrator.sanitize("Auth secret=mySecretValue rejected"))
                    .isEqualTo("Auth secret=[REDACTED] rejected");

            // password
            assertThat(PlaylistMigrationOrchestrator.sanitize("Login failed for password=SuperSecretPassword123!"))
                    .isEqualTo("Login failed for password=[REDACTED]");

            // null and non-credential strings
            assertThat(PlaylistMigrationOrchestrator.sanitize(null)).isNull();
            assertThat(PlaylistMigrationOrchestrator.sanitize("Normal message with track name Bohemian Rhapsody"))
                    .isEqualTo("Normal message with track name Bohemian Rhapsody");
        }
    }

    private TrackMatchResult createMatchResult(
            String sourceTrackId,
            NormalizedTrackMetadata norm,
            DestinationTrackCandidate candidate,
            MatchDecision decision,
            Double score,
            Double calibratedProb,
            List<String> reasons
    ) {
        return TrackMatchResult.of(
                sourceTrackId,
                norm,
                decision,
                candidate,
                score,
                calibratedProb,
                null,
                null,
                List.of(),
                reasons != null ? reasons : List.of()
        );
    }

    private NormalizedTrackMetadata createNormalizedMetadata(String title, String artist) {
        return new NormalizedTrackMetadata(
                RawTrackMetadata.of(title, artist, "Album", 200000L),
                title.toLowerCase(),
                new com.spotifyyoutube.migrator.matching.normalization.NormalizedArtistCredit(artist.toLowerCase(), List.of()),
                List.of(artist.toLowerCase()),
                List.of(),
                "album",
                Duration.ofMillis(200000L),
                new com.spotifyyoutube.migrator.matching.normalization.TokenizedTitle(
                        title, title.toLowerCase(), false, java.util.Set.of(), null, null, java.util.Set.of(), List.of(), List.of(), List.of()
                )
        );
    }

    private DestinationTrackCandidate createCandidate(String id, String title) {
        return new DestinationTrackCandidate(
                id,
                title,
                "Channel",
                "Description",
                "https://thumb.jpg",
                "2021-01-01",
                Duration.ofMillis(200000L)
        );
    }
}
