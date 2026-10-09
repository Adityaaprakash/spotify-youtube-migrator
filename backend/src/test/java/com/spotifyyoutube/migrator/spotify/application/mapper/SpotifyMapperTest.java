package com.spotifyyoutube.migrator.spotify.application.mapper;

import com.spotifyyoutube.migrator.spotify.api.dto.SpotifyPageResponse;
import com.spotifyyoutube.migrator.spotify.api.dto.SpotifyPlaylistSummaryResponse;
import com.spotifyyoutube.migrator.spotify.api.dto.SpotifyProfileResponse;
import com.spotifyyoutube.migrator.spotify.infrastructure.dto.*;
import org.junit.jupiter.api.Test;

import java.util.List;

import com.spotifyyoutube.migrator.identity.domain.User;
import com.spotifyyoutube.migrator.playlist.domain.Platform;
import com.spotifyyoutube.migrator.playlist.domain.Playlist;
import com.spotifyyoutube.migrator.playlist.domain.Track;

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

    @Test
    void toPlaylistDomain_shouldMapCorrectly() {
        SpotifyPlaylistSummaryDto dto = new SpotifyPlaylistSummaryDto(
                "pl123", "My Playlist", "Desc",
                new SpotifyOwnerDto("owner1", "Owner Name"),
                List.of(new SpotifyImageDto("http://playlist.img", null, null)),
                new SpotifyTracksDto(null, 42),
                new SpotifyExternalUrlsDto("http://spotify.url")
        );
        User user = new User();

        Playlist domain = mapper.toPlaylistDomain(dto, user);

        assertThat(domain).isNotNull();
        assertThat(domain.getPlatform()).isEqualTo(Platform.SPOTIFY);
        assertThat(domain.getExternalId()).isEqualTo("pl123");
        assertThat(domain.getName()).isEqualTo("My Playlist");
        assertThat(domain.getDescription()).isEqualTo("Desc");
        assertThat(domain.getImageUrl()).isEqualTo("http://playlist.img");
        assertThat(domain.getUrl()).isEqualTo("http://spotify.url");
        assertThat(domain.getTotalTracks()).isEqualTo(42);
        assertThat(domain.getUser()).isEqualTo(user);
    }

    @Test
    void toPlaylistDomain_shouldHandleNullsAndMissingOptionals() {
        SpotifyPlaylistSummaryDto dto = new SpotifyPlaylistSummaryDto(
                "pl123", null, null, null, null, null, null
        );

        Playlist domain = mapper.toPlaylistDomain(dto, null);

        assertThat(domain).isNotNull();
        assertThat(domain.getName()).isEqualTo("Unknown Playlist");
        assertThat(domain.getDescription()).isNull();
        assertThat(domain.getImageUrl()).isNull();
        assertThat(domain.getUrl()).isNull();
        assertThat(domain.getTotalTracks()).isNull();
    }

    @Test
    void toTrackDomain_shouldMapCorrectly() {
        SpotifyTrackDto dto = new SpotifyTrackDto(
                "t123", "Song", 200000, "spotify:track:t123", false,
                List.of(new SpotifyArtistDto("a1", "Artist 1"), new SpotifyArtistDto("a2", "Artist 2")),
                new SpotifyAlbumDto("al1", "Album")
        );
        Playlist playlist = new Playlist();

        Track track = mapper.toTrackDomain(dto, playlist);

        assertThat(track).isNotNull();
        assertThat(track.getExternalId()).isEqualTo("t123");
        assertThat(track.getName()).isEqualTo("Song");
        assertThat(track.getArtist()).isEqualTo("Artist 1, Artist 2");
        assertThat(track.getAlbum()).isEqualTo("Album");
        assertThat(track.getDurationMs()).isEqualTo(200000);
        assertThat(track.getPlaylist()).isEqualTo(playlist);
    }

    @Test
    void toTrackDomain_shouldHandleNulls() {
        SpotifyTrackDto dto = new SpotifyTrackDto(
                null, null, null, null, null, null, null
        );

        Track track = mapper.toTrackDomain(dto, null);

        assertThat(track).isNotNull();
        assertThat(track.getExternalId()).startsWith("local-");
        assertThat(track.getName()).isEqualTo("Unknown Track");
        assertThat(track.getArtist()).isEqualTo("Unknown Artist");
        assertThat(track.getAlbum()).isNull();
        assertThat(track.getDurationMs()).isEqualTo(0);
    }
}
