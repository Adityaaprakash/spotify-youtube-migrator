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
                    throw new ExternalProviderException("Failed to fetch Spotify profile. Status: " + res.getStatusCode());
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
                    throw new ExternalProviderException("Failed to fetch Spotify playlists. Status: " + res.getStatusCode());
                })
                .body(new ParameterizedTypeReference<SpotifyPagingDto<SpotifyPlaylistSummaryDto>>() {});
    }
}
