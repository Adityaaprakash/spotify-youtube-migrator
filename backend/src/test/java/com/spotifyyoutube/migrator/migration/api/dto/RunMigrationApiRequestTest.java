package com.spotifyyoutube.migrator.migration.api.dto;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link RunMigrationApiRequest} (Phase 12E & 12G).
 */
@DisplayName("Phase 12E: RunMigrationApiRequest Test")
class RunMigrationApiRequestTest {

    @Test
    @DisplayName("Canonical constructor retains all fields accurately")
    void canonicalConstructor() {
        UUID sessionId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();

        RunMigrationApiRequest request = new RunMigrationApiRequest(
                sessionId,
                userId,
                "sp_playlist_1",
                "Target Title",
                "Target Desc",
                true
        );

        assertThat(request.reviewSessionId()).isEqualTo(sessionId);
        assertThat(request.userId()).isEqualTo(userId);
        assertThat(request.sourcePlaylistId()).isEqualTo("sp_playlist_1");
        assertThat(request.targetPlaylistTitle()).isEqualTo("Target Title");
        assertThat(request.targetPlaylistDescription()).isEqualTo("Target Desc");
        assertThat(request.isPrivate()).isTrue();
    }

    @Test
    @DisplayName("Single-argument convenience constructor populates defaults for optional fields")
    void convenienceConstructor() {
        UUID sessionId = UUID.randomUUID();

        RunMigrationApiRequest request = new RunMigrationApiRequest(sessionId);

        assertThat(request.reviewSessionId()).isEqualTo(sessionId);
        assertThat(request.userId()).isNull();
        assertThat(request.sourcePlaylistId()).isNull();
        assertThat(request.targetPlaylistTitle()).isNull();
        assertThat(request.targetPlaylistDescription()).isNull();
        assertThat(request.isPrivate()).isNull();
    }
}
