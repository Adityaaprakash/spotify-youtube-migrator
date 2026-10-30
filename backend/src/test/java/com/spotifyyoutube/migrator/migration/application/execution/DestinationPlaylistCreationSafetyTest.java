package com.spotifyyoutube.migrator.migration.application.execution;

import com.spotifyyoutube.migrator.identity.domain.User;
import com.spotifyyoutube.migrator.matching.discovery.CandidateDiscoveryService;
import com.spotifyyoutube.migrator.matching.engine.TrackMatchingEngine;
import com.spotifyyoutube.migrator.matching.normalization.NormalizationOrchestrator;
import com.spotifyyoutube.migrator.migration.application.destination.MusicDestination;
import com.spotifyyoutube.migrator.migration.application.destination.model.CreateDestinationPlaylistRequest;
import com.spotifyyoutube.migrator.migration.application.destination.model.DestinationPlaylist;
import com.spotifyyoutube.migrator.migration.application.execution.contract.ApprovedMigrationContext;
import com.spotifyyoutube.migrator.migration.application.execution.contract.ApprovedMigrationExecutionRequest;
import com.spotifyyoutube.migrator.migration.application.execution.contract.ApprovedTrack;
import com.spotifyyoutube.migrator.migration.application.execution.contract.DestinationPlaylistCreationResult;
import com.spotifyyoutube.migrator.migration.application.execution.validation.DefaultApprovedPlanValidator;
import com.spotifyyoutube.migrator.migration.review.domain.ReviewDecisionSource;
import com.spotifyyoutube.migrator.migration.review.domain.ReviewSession;
import com.spotifyyoutube.migrator.migration.review.domain.ReviewTrack;
import com.spotifyyoutube.migrator.migration.review.domain.ReviewTrackStatus;
import com.spotifyyoutube.migrator.migration.review.repository.ReviewSessionRepository;
import com.spotifyyoutube.migrator.migration.review.repository.ReviewTrackRepository;
import com.spotifyyoutube.migrator.playlist.domain.Platform;
import com.spotifyyoutube.migrator.spotify.application.SpotifyPlaylistService;
import com.spotifyyoutube.migrator.youtube.application.YouTubePlaylistService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("Phase 11C: Destination Playlist Creation Safety & Mutation Boundary Test")
class DestinationPlaylistCreationSafetyTest {

    @Test
    @DisplayName("Reflection Safety: DefaultDestinationPlaylistCreationService holds zero matching or discovery dependencies")
    void playlistCreationServiceHoldsZeroPipelineDependencies() {
        Field[] fields = DefaultDestinationPlaylistCreationService.class.getDeclaredFields();
        for (Field field : fields) {
            assertThat(field.getType())
                    .as("Creation service field '%s' must not be TrackMatchingEngine, NormalizationOrchestrator, or CandidateDiscoveryService", field.getName())
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
    @DisplayName("Strict Mutation Boundary: Destination playlist creation never invokes addTrackToPlaylist")
    void destinationPlaylistCreationNeverAddsTracks() {
        ApprovedMigrationExecutionService executionService = mock(ApprovedMigrationExecutionService.class);
        MusicDestination musicDestination = mock(MusicDestination.class);
        DestinationPlaylistCreationService creationService = new DefaultDestinationPlaylistCreationService(
                executionService, musicDestination
        );

        UUID userId = UUID.randomUUID();
        UUID sessionId = UUID.randomUUID();

        ApprovedTrack track0 = new ApprovedTrack(
                0, "sp-0", "Song 0", "Artist 0", "yt-0", "Title 0", "Channel 0",
                Duration.ofSeconds(200), ReviewDecisionSource.AUTOMATIC, 0
        );
        ApprovedMigrationContext context = new ApprovedMigrationContext(
                sessionId, UUID.randomUUID(), userId, "sp-pl-1", "Playlist Title",
                "Migrated - Playlist Title", "Description", true,
                List.of(track0), 1, 1, 0, Instant.now()
        );

        when(executionService.prepareExecution(any(ApprovedMigrationExecutionRequest.class), eq(userId)))
                .thenReturn(context);
        when(musicDestination.getPlatform()).thenReturn(Platform.YOUTUBE);
        when(musicDestination.createPlaylist(eq(userId), any(CreateDestinationPlaylistRequest.class)))
                .thenReturn(new DestinationPlaylist("PL_safety_123", "Title", "Desc", "private", "http://yt.com", Platform.YOUTUBE));

        DestinationPlaylistCreationResult result = creationService.createDestinationPlaylist(sessionId, userId);

        assertThat(result.destinationPlaylistId()).isEqualTo("PL_safety_123");

        // Verify playlist creation invoked exactly once
        verify(musicDestination, times(1)).createPlaylist(eq(userId), any());

        // STRICT INVARIANT: addTrackToPlaylist is NEVER called during Phase 11C
        verify(musicDestination, never()).addTrackToPlaylist(any(), any(), any());
    }

    @Test
    @DisplayName("Reflection Safety: DestinationPlaylistCreationService declares no methods named addTrack or insertTrack")
    void interfaceDeclaresNoTrackInsertionMethods() {
        Method[] methods = DestinationPlaylistCreationService.class.getDeclaredMethods();
        for (Method method : methods) {
            assertThat(method.getName().toLowerCase())
                    .as("Method '%s' must not perform track insertion", method.getName())
                    .doesNotContain("addtrack")
                    .doesNotContain("inserttrack");
        }
    }
}
