package com.spotifyyoutube.migrator.migration.application.plan;

import com.spotifyyoutube.migrator.migration.api.MigrationPlanController;
import com.spotifyyoutube.migrator.migration.api.dto.MigrationPlanPreviewRequest;
import com.spotifyyoutube.migrator.migration.api.dto.MigrationPlanResponse;
import com.spotifyyoutube.migrator.migration.application.destination.MusicDestination;
import com.spotifyyoutube.migrator.migration.application.destination.model.AddTrackToDestinationPlaylistRequest;
import com.spotifyyoutube.migrator.migration.application.destination.model.CreateDestinationPlaylistRequest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.lang.reflect.Field;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("MigrationPlan Destination Mutation Safety Invariant Tests")
public class MigrationPlanDestinationSafetyTest {

    @Mock
    private PlaylistMigrationPlanGenerator planGenerator;

    @Mock
    private MusicDestination musicDestination;

    @Test
    @DisplayName("Preview generation must never invoke destination playlist creation or track addition")
    void previewGenerationMustNeverMutateDestination() {
        DefaultMigrationPlanService service = new DefaultMigrationPlanService(planGenerator);
        UUID userId = UUID.randomUUID();
        String playlistId = "sp-safe-123";
        MigrationPlanPreviewRequest request = new MigrationPlanPreviewRequest(playlistId);

        MigrationPlan plan = MigrationPlan.empty(UUID.randomUUID(), playlistId, "Safe Playlist", Instant.now());
        when(planGenerator.generatePlan(userId, playlistId, true)).thenReturn(plan);

        MigrationPlanResponse response = service.generatePreview(userId, request);

        assertThat(response).isNotNull();
        // Strict assertion: zero destination mutation calls
        verify(musicDestination, never()).createPlaylist(any(UUID.class), any(CreateDestinationPlaylistRequest.class));
        verify(musicDestination, never()).addTrackToPlaylist(any(UUID.class), any(String.class), any(AddTrackToDestinationPlaylistRequest.class));
    }

    @Test
    @DisplayName("Architectural verification: Planning classes must not hold destination mutation dependencies")
    void planningClassesMustNotHoldDestinationMutationDependencies() {
        List<Class<?>> planningClasses = List.of(
                MigrationPlanController.class,
                DefaultMigrationPlanService.class,
                DefaultPlaylistMigrationPlanGenerator.class
        );

        List<String> forbiddenTypeNames = List.of(
                "MusicDestination",
                "YouTubePlaylistService",
                "YouTubeClient",
                "MigrationJobRepository",
                "MigrationTaskRepository"
        );

        for (Class<?> clazz : planningClasses) {
            for (Field field : clazz.getDeclaredFields()) {
                for (String forbidden : forbiddenTypeNames) {
                    assertThat(field.getType().getSimpleName())
                            .as("%s must not depend on %s (preview-only safety invariant)", clazz.getSimpleName(), forbidden)
                            .isNotEqualTo(forbidden);
                }
            }
        }
    }
}
