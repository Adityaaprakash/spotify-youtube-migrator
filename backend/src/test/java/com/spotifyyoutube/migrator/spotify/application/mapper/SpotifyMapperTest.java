package com.spotifyyoutube.migrator.spotify.application.mapper;

import com.spotifyyoutube.migrator.spotify.api.dto.SpotifyPageResponse;
import com.spotifyyoutube.migrator.spotify.api.dto.SpotifyPlaylistSummaryResponse;
import com.spotifyyoutube.migrator.spotify.api.dto.SpotifyProfileResponse;
import com.spotifyyoutube.migrator.spotify.infrastructure.dto.*;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class SpotifyMapperTest {

    private final SpotifyMapper mapper = new SpotifyMapper();

    @Test
    void mapProfile_shouldHandleCompleteProfile() {
        SpotifyUserProfileDto dto = new SpotifyUserProfileDto(
                "user123", "Test User", "test@example.com",
                List.of(new SpotifyImageDto("http://image.url", 300, 300))
        );

        SpotifyProfileResponse response = mapper.mapProfile(dto);

        assertThat(response.id()).isEqualTo("user123");
        assertThat(response.displayName()).isEqualTo("Test User");
        assertThat(response.email()).isEqualTo("test@example.com");
        assertThat(response.imageUrl()).isEqualTo("http://image.url");
    }

    @Test
    void mapProfile_shouldHandleMissingImagesAndEmail() {
        SpotifyUserProfileDto dto = new SpotifyUserProfileDto(
                "user123", null, null, null
        );

        SpotifyProfileResponse response = mapper.mapProfile(dto);

        assertThat(response.id()).isEqualTo("user123");
        assertThat(response.displayName()).isNull();
        assertThat(response.email()).isNull();
        assertThat(response.imageUrl()).isNull();
    }

    @Test
    void mapPlaylistSummary_shouldHandleCompleteSummary() {
        SpotifyPlaylistSummaryDto dto = new SpotifyPlaylistSummaryDto(
                "pl123", "My Playlist", "Desc",
                new SpotifyOwnerDto("owner1", "Owner Name"),
                List.of(new SpotifyImageDto("http://playlist.img", null, null)),
                new SpotifyTracksDto(null, 42),
                new SpotifyExternalUrlsDto("http://spotify.url")
        );

        SpotifyPlaylistSummaryResponse response = mapper.mapPlaylistSummary(dto);

        assertThat(response.id()).isEqualTo("pl123");
        assertThat(response.name()).isEqualTo("My Playlist");
        assertThat(response.description()).isEqualTo("Desc");
        assertThat(response.ownerDisplayName()).isEqualTo("Owner Name");
        assertThat(response.imageUrl()).isEqualTo("http://playlist.img");
        assertThat(response.totalTracks()).isEqualTo(42);
        assertThat(response.spotifyUrl()).isEqualTo("http://spotify.url");
    }

    @Test
    void mapPlaylistSummary_shouldHandleMissingOwnerAndOptionalMetadata() {
        SpotifyPlaylistSummaryDto dto = new SpotifyPlaylistSummaryDto(
                "pl123", "No Meta", null, null, null, null, null
        );

        SpotifyPlaylistSummaryResponse response = mapper.mapPlaylistSummary(dto);

        assertThat(response.id()).isEqualTo("pl123");
        assertThat(response.name()).isEqualTo("No Meta");
        assertThat(response.description()).isNull();
        assertThat(response.ownerDisplayName()).isNull();
        assertThat(response.imageUrl()).isNull();
        assertThat(response.totalTracks()).isNull();
        assertThat(response.spotifyUrl()).isNull();
    }

    @Test
    void mapPaging_shouldHandleNextLinkPresence() {
        SpotifyPagingDto<String> dto = new SpotifyPagingDto<>(
                "href", List.of("1"), 50, 0, "http://next", null, 100
        );

        SpotifyPageResponse<String> response = mapper.mapPaging(dto, List.of("1"));

        assertThat(response.items()).containsExactly("1");
        assertThat(response.total()).isEqualTo(100);
        assertThat(response.limit()).isEqualTo(50);
        assertThat(response.offset()).isEqualTo(0);
        assertThat(response.hasNext()).isTrue();
    }

    @Test
    void mapPaging_shouldHandleMissingNextLink() {
        SpotifyPagingDto<String> dto = new SpotifyPagingDto<>(
                "href", List.of("1"), 50, 50, null, "http://prev", 51
        );

        SpotifyPageResponse<String> response = mapper.mapPaging(dto, List.of("1"));

        assertThat(response.hasNext()).isFalse();
    }
}
