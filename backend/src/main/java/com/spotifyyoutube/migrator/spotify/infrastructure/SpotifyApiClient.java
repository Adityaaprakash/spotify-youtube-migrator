package com.spotifyyoutube.migrator.spotify.infrastructure;

import com.spotifyyoutube.migrator.common.exception.ExternalProviderException;
import com.spotifyyoutube.migrator.spotify.infrastructure.dto.SpotifyPagingDto;
import com.spotifyyoutube.migrator.spotify.infrastructure.dto.SpotifyPlaylistSummaryDto;
import com.spotifyyoutube.migrator.spotify.infrastructure.dto.SpotifyUserProfileDto;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriComponentsBuilder;

@Component
public class SpotifyApiClient {

    private final RestClient restClient;

    public SpotifyApiClient(RestClient.Builder restClientBuilder) {
        this.restClient = restClientBuilder
                .baseUrl("https://api.spotify.com/v1")
                .build();
    }

    public SpotifyUserProfileDto getCurrentUserProfile(String accessToken) {
        return restClient.get()
                .uri("/me")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)
                .retrieve()
                .onStatus(status -> status.isError(), (req, res) -> {
                    String retryAfter = res.getHeaders().getFirst(HttpHeaders.RETRY_AFTER);
                    throw new ExternalProviderException("Failed to fetch Spotify profile", res.getStatusCode().value(), retryAfter);
                })
                .body(SpotifyUserProfileDto.class);
    }

    public SpotifyPagingDto<SpotifyPlaylistSummaryDto> getUserPlaylists(String accessToken, int limit, int offset) {
        String uri = UriComponentsBuilder.fromPath("/me/playlists")
                .queryParam("limit", limit)
                .queryParam("offset", offset)
                .build()
                .toUriString();

        return restClient.get()
                .uri(uri)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)
                .retrieve()
                .onStatus(status -> status.isError(), (req, res) -> {
                    String retryAfter = res.getHeaders().getFirst(HttpHeaders.RETRY_AFTER);
                    throw new ExternalProviderException("Failed to fetch Spotify playlists", res.getStatusCode().value(), retryAfter);
                })
                .body(new ParameterizedTypeReference<SpotifyPagingDto<SpotifyPlaylistSummaryDto>>() {});
    }

    public SpotifyPlaylistSummaryDto getPlaylistMetadata(String accessToken, String playlistId) {
        String uri = UriComponentsBuilder.fromPath("/playlists/{id}")
                .buildAndExpand(playlistId)
                .toUriString();

        return restClient.get()
                .uri(uri)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)
                .retrieve()
                .onStatus(status -> status.isError(), (req, res) -> {
                    if (res.getStatusCode().value() == 404) {
                        throw new com.spotifyyoutube.migrator.common.exception.ResourceNotFoundException("Playlist not found on Spotify");
                    }
                    String retryAfter = res.getHeaders().getFirst(HttpHeaders.RETRY_AFTER);
                    throw new ExternalProviderException("Failed to fetch Spotify playlist metadata", res.getStatusCode().value(), retryAfter);
                })
                .body(SpotifyPlaylistSummaryDto.class);
    }

    public com.spotifyyoutube.migrator.spotify.infrastructure.dto.SpotifyPagingDto<com.spotifyyoutube.migrator.spotify.infrastructure.dto.SpotifyPlaylistTrackDto> getPlaylistTracks(String accessToken, String playlistId, int limit, int offset) {
        String uri = UriComponentsBuilder.fromPath("/playlists/{id}/tracks")
                .queryParam("limit", limit)
                .queryParam("offset", offset)
                .buildAndExpand(playlistId)
                .toUriString();

        return restClient.get()
                .uri(uri)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)
                .retrieve()
                .onStatus(status -> status.isError(), (req, res) -> {
                    if (res.getStatusCode().value() == 404) {
                        throw new com.spotifyyoutube.migrator.common.exception.ResourceNotFoundException("Playlist not found on Spotify for tracks");
                    }
                    String retryAfter = res.getHeaders().getFirst(HttpHeaders.RETRY_AFTER);
                    throw new ExternalProviderException("Failed to fetch Spotify playlist tracks", res.getStatusCode().value(), retryAfter);
                })
                .body(new ParameterizedTypeReference<com.spotifyyoutube.migrator.spotify.infrastructure.dto.SpotifyPagingDto<com.spotifyyoutube.migrator.spotify.infrastructure.dto.SpotifyPlaylistTrackDto>>() {});
    }
}
