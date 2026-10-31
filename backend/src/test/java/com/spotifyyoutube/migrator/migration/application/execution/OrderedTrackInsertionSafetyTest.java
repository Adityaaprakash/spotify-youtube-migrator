package com.spotifyyoutube.migrator.migration.application.execution;

import com.spotifyyoutube.migrator.matching.discovery.CandidateDiscoveryService;
import com.spotifyyoutube.migrator.matching.engine.TrackMatchingEngine;
import com.spotifyyoutube.migrator.matching.normalization.NormalizationOrchestrator;
import com.spotifyyoutube.migrator.migration.application.destination.MusicDestination;
import com.spotifyyoutube.migrator.migration.application.destination.model.AddTrackToDestinationPlaylistRequest;
import com.spotifyyoutube.migrator.migration.application.destination.model.DestinationPlaylistItem;
import com.spotifyyoutube.migrator.migration.application.execution.contract.ApprovedMigrationContext;
import com.spotifyyoutube.migrator.migration.application.execution.contract.ApprovedMigrationExecutionRequest;
import com.spotifyyoutube.migrator.migration.application.execution.contract.ApprovedTrack;
import com.spotifyyoutube.migrator.migration.application.execution.contract.ExecutionValidationError;
import com.spotifyyoutube.migrator.migration.application.execution.contract.ExecutionValidationResult;
import com.spotifyyoutube.migrator.migration.application.execution.contract.InsertTracksExecutionRequest;
import com.spotifyyoutube.migrator.migration.application.execution.contract.MigrationExecutionValidationException;
import com.spotifyyoutube.migrator.migration.application.execution.contract.PlaylistTrackInsertionSummary;
import com.spotifyyoutube.migrator.migration.application.execution.contract.ValidationFailureCode;
import com.spotifyyoutube.migrator.migration.application.execution.validation.DefaultApprovedPlanValidator;
import com.spotifyyoutube.migrator.migration.review.domain.ReviewDecisionSource;
import com.spotifyyoutube.migrator.migration.review.repository.ReviewSessionRepository;
import com.spotifyyoutube.migrator.migration.review.repository.ReviewTrackRepository;
import com.spotifyyoutube.migrator.playlist.domain.Platform;
import com.spotifyyoutube.migrator.spotify.application.SpotifyPlaylistService;
import com.spotifyyoutube.migrator.youtube.application.YouTubePlaylistService;
import com.spotifyyoutube.migrator.youtube.dto.YouTubePlaylistDto;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.scheduling.annotation.Async;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@DisplayName("Phase 11D: Ordered Track Insertion Safety & Invariants Test")
class OrderedTrackInsertionSafetyTest {

    @Test
    @DisplayName("Reflection Safety: DefaultOrderedTrackInsertionService holds zero matching or discovery pipeline dependencies")
    void insertionServiceHoldsZeroPipelineDependencies() {
        Field[] fields = DefaultOrderedTrackInsertionService.class.getDeclaredFields();
        for (Field field : fields) {
            assertThat(field.getType())
                    .as("Insertion service field '%s' must not be TrackMatchingEngine, NormalizationOrchestrator, or CandidateDiscoveryService", field.getName())
                    .isNotEqualTo(TrackMatchingEngine.class)
                    .isNotEqualTo(NormalizationOrchestrator.class)
                    .isNotEqualTo(CandidateDiscoveryService.class)
                    .isNotEqualTo(SpotifyPlaylistService.class)
                    .isNotEqualTo(ReviewSessionRepository.class)
                    .isNotEqualTo(ReviewTrackRepository.class);
        }
    }

    @Test
    @DisplayName("Reflection Safety: DefaultApprovedPlanValidator holds zero destination dependencies (ADR-024 invariant)")
    void validatorHoldsZeroDestinationDependencies() {
        Field[] fields = DefaultApprovedPlanValidator.class.getDeclaredFields();
        for (Field field : fields) {
            assertThat(field.getType())
                    .as("Validator field '%s' must not be MusicDestination or YouTubePlaylistService", field.getName())
                    .isNotEqualTo(MusicDestination.class)
                    .isNotEqualTo(YouTubePlaylistService.class);
        }
    }

    @Test
    @DisplayName("Reflection Safety: DefaultApprovedMigrationExecutionService holds zero destination dependencies (ADR-024 invariant)")
    void executionServiceHoldsZeroDestinationDependencies() {
        Field[] fields = DefaultApprovedMigrationExecutionService.class.getDeclaredFields();
        for (Field field : fields) {
            assertThat(field.getType())
                    .as("Execution service field '%s' must not be MusicDestination or YouTubePlaylistService", field.getName())
                    .isNotEqualTo(MusicDestination.class)
                    .isNotEqualTo(YouTubePlaylistService.class);
        }
    }

    @Test
    @DisplayName("Strict Mutation Boundary: Ordered track insertion service never creates playlists (preserves Phase 11C boundary)")
    void insertionServiceNeverCreatesPlaylists() {
        Method[] methods = OrderedTrackInsertionService.class.getDeclaredMethods();
        for (Method method : methods) {
            assertThat(method.getName().toLowerCase())
                    .as("Method '%s' must not create playlists", method.getName())
                    .doesNotContain("createplaylist");
        }
    }

    @Test
    @DisplayName("No Asynchronous Infrastructure: Service contains no Future, CompletableFuture, or @Async methods")
    void noAsyncInfrastructure() {
        Method[] methods = DefaultOrderedTrackInsertionService.class.getDeclaredMethods();
        for (Method method : methods) {
            assertThat(method.getReturnType())
                    .as("Method '%s' must not return async Future", method.getName())
                    .isNotEqualTo(CompletableFuture.class)
                    .isNotEqualTo(Future.class);

            assertThat(method.isAnnotationPresent(Async.class))
                    .as("Method '%s' must not have @Async annotation", method.getName())
                    .isFalse();
        }
    }

    @Test
    @DisplayName("Validation Failure Safety: Zero provider calls if execution preparation fails")
    void zeroProviderCallsOnValidationFailure() {
        ApprovedMigrationExecutionService executionService = mock(ApprovedMigrationExecutionService.class);
        MusicDestination musicDestination = mock(MusicDestination.class);
        YouTubePlaylistService youTubePlaylistService = mock(YouTubePlaylistService.class);

        OrderedTrackInsertionService service = new DefaultOrderedTrackInsertionService(
                executionService, musicDestination, youTubePlaylistService
        );

        UUID userId = UUID.randomUUID();
        UUID sessionId = UUID.randomUUID();
        InsertTracksExecutionRequest req = InsertTracksExecutionRequest.of(sessionId, "PL_dest_1", userId);

        ExecutionValidationError error = new ExecutionValidationError(ValidationFailureCode.UNRESOLVED_DECISIONS, 0, "sp-0", "Track unresolved");
        ExecutionValidationResult invalidResult = ExecutionValidationResult.invalid(List.of(error));
        when(executionService.prepareExecution(any(ApprovedMigrationExecutionRequest.class), eq(userId)))
                .thenThrow(new MigrationExecutionValidationException(invalidResult));

        assertThatThrownBy(() -> service.insertApprovedTracks(req, userId))
                .isInstanceOf(MigrationExecutionValidationException.class);

        // Crucial invariant: zero provider mutations
        verifyNoInteractions(musicDestination);
        verifyNoInteractions(youTubePlaylistService);
    }

    @Test
    @DisplayName("Sequential Insertion Order: 4 tracks inserted with strict monotonic 0-indexed positions (0, 1, 2, 3)")
    void strictlyMonotonicSequentialInsertionOrder() {
        ApprovedMigrationExecutionService executionService = mock(ApprovedMigrationExecutionService.class);
        MusicDestination musicDestination = mock(MusicDestination.class);
        YouTubePlaylistService youTubePlaylistService = mock(YouTubePlaylistService.class);

        OrderedTrackInsertionService service = new DefaultOrderedTrackInsertionService(
                executionService, musicDestination, youTubePlaylistService
        );

        UUID userId = UUID.randomUUID();
        UUID sessionId = UUID.randomUUID();
        String playlistId = "PL_strict_order";
        InsertTracksExecutionRequest req = InsertTracksExecutionRequest.of(sessionId, playlistId, userId);

        ApprovedTrack t0 = new ApprovedTrack(0, "s0", "T0", "A0", "y0", "D0", "C0", Duration.ofSeconds(100), ReviewDecisionSource.AUTOMATIC, 0);
        ApprovedTrack t1 = new ApprovedTrack(1, "s1", "T1", "A1", "y1", "D1", "C1", Duration.ofSeconds(100), ReviewDecisionSource.AUTOMATIC, 1);
        ApprovedTrack t2 = new ApprovedTrack(2, "s2", "T2", "A2", "y2", "D2", "C2", Duration.ofSeconds(100), ReviewDecisionSource.AUTOMATIC, 2);
        ApprovedTrack t3 = new ApprovedTrack(3, "s3", "T3", "A3", "y3", "D3", "C3", Duration.ofSeconds(100), ReviewDecisionSource.AUTOMATIC, 3);

        ApprovedMigrationContext context = new ApprovedMigrationContext(
                sessionId, UUID.randomUUID(), userId, "sp-pl", "Title",
                "Migrated Title", "Desc", true, List.of(t0, t1, t2, t3), 4, 4, 0, Instant.now()
        );

        when(executionService.prepareExecution(any(), eq(userId))).thenReturn(context);
        when(musicDestination.getPlatform()).thenReturn(Platform.YOUTUBE);
        when(youTubePlaylistService.getPlaylist(userId, playlistId))
                .thenReturn(new YouTubePlaylistDto(playlistId, "Title", "Desc", "private", 0L, null));

        when(musicDestination.addTrackToPlaylist(eq(userId), eq(playlistId), any()))
                .thenReturn(new DestinationPlaylistItem("it0", playlistId, "y0", 0))
                .thenReturn(new DestinationPlaylistItem("it1", playlistId, "y1", 1))
                .thenReturn(new DestinationPlaylistItem("it2", playlistId, "y2", 2))
                .thenReturn(new DestinationPlaylistItem("it3", playlistId, "y3", 3));

        PlaylistTrackInsertionSummary summary = service.insertApprovedTracks(req, userId);

        assertThat(summary.fullySuccessful()).isTrue();
        assertThat(summary.totalApprovedTracks()).isEqualTo(4);

        InOrder inOrder = inOrder(musicDestination);
        inOrder.verify(musicDestination).addTrackToPlaylist(userId, playlistId, new AddTrackToDestinationPlaylistRequest("y0", 0));
        inOrder.verify(musicDestination).addTrackToPlaylist(userId, playlistId, new AddTrackToDestinationPlaylistRequest("y1", 1));
        inOrder.verify(musicDestination).addTrackToPlaylist(userId, playlistId, new AddTrackToDestinationPlaylistRequest("y2", 2));
        inOrder.verify(musicDestination).addTrackToPlaylist(userId, playlistId, new AddTrackToDestinationPlaylistRequest("y3", 3));
        inOrder.verifyNoMoreInteractions();

        // Never calls createPlaylist
        verify(musicDestination, never()).createPlaylist(any(), any());
    }
}
