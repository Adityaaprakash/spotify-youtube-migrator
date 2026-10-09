package com.spotifyyoutube.migrator.spotify.infrastructure;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.spotifyyoutube.migrator.common.exception.ExternalProviderException;
import com.spotifyyoutube.migrator.spotify.infrastructure.dto.SpotifyPagingDto;
import com.spotifyyoutube.migrator.spotify.infrastructure.dto.SpotifyPlaylistSummaryDto;
import com.spotifyyoutube.migrator.spotify.infrastructure.dto.SpotifyPlaylistTrackDto;
import com.spotifyyoutube.migrator.spotify.infrastructure.dto.SpotifyUserProfileDto;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.client.RestClientTest;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;

@RestClientTest(SpotifyApiClient.class)
class SpotifyApiClientTest {

    @Autowired
    private SpotifyApiClient spotifyApiClient;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private MockRestServiceServer mockServer;

    @BeforeEach
    void setUp() {
        mockServer.reset();
    }

    @Test
    void getCurrentUserProfile_shouldReturnProfile_onSuccess() throws Exception {
        SpotifyUserProfileDto mockResponse = new SpotifyUserProfileDto("u123", "User", "test@test.com", null);

        mockServer.expect(requestTo("https://api.spotify.com/v1/me"))
                .andExpect(method(HttpMethod.GET))
                .andExpect(header("Authorization", "Bearer valid_token"))
                .andRespond(withSuccess(objectMapper.writeValueAsString(mockResponse), MediaType.APPLICATION_JSON));

        SpotifyUserProfileDto dto = spotifyApiClient.getCurrentUserProfile("valid_token");
        
        assertThat(dto).isNotNull();
        assertThat(dto.id()).isEqualTo("u123");
        mockServer.verify();
    }

    @Test
    void getCurrentUserProfile_shouldThrowExternalProviderException_onError() {
        mockServer.expect(requestTo("https://api.spotify.com/v1/me"))
                .andRespond(withServerError());

        assertThatThrownBy(() -> spotifyApiClient.getCurrentUserProfile("valid_token"))
                .isInstanceOf(ExternalProviderException.class)
                .hasMessageStartingWith("Failed to fetch Spotify profile")
                .satisfies(e -> {
                    ExternalProviderException ext = (ExternalProviderException) e;
                    assertThat(ext.getProviderStatusCode()).isEqualTo(500);
                });
    }

    @Test
    void getUserPlaylists_shouldReturnPaging_onSuccess() throws Exception {
        String mockResponseBody = """
        {
            "href": "https://api.spotify.com/v1/me/playlists",
            "items": [],
            "limit": 50,
            "offset": 0,
            "total": 0
        }
        """;

        mockServer.expect(requestTo("https://api.spotify.com/v1/me/playlists?limit=50&offset=0"))
                .andExpect(method(HttpMethod.GET))
                .andExpect(header("Authorization", "Bearer valid_token"))
                .andRespond(withSuccess(mockResponseBody, MediaType.APPLICATION_JSON));

        SpotifyPagingDto<SpotifyPlaylistSummaryDto> result = spotifyApiClient.getUserPlaylists("valid_token", 50, 0);
        
        assertThat(result).isNotNull();
        assertThat(result.limit()).isEqualTo(50);
        assertThat(result.total()).isEqualTo(0);
        mockServer.verify();
    }

    @Test
    void getPlaylistMetadata_shouldReturnSummary_onSuccess() throws Exception {
        String mockResponseBody = """
        {
            "id": "p123",
            "name": "My Playlist"
        }
        """;

        mockServer.expect(requestTo("https://api.spotify.com/v1/playlists/p123"))
                .andExpect(method(HttpMethod.GET))
                .andExpect(header("Authorization", "Bearer valid_token"))
                .andRespond(withSuccess(mockResponseBody, MediaType.APPLICATION_JSON));

        SpotifyPlaylistSummaryDto result = spotifyApiClient.getPlaylistMetadata("valid_token", "p123");
        
        assertThat(result).isNotNull();
        assertThat(result.id()).isEqualTo("p123");
        assertThat(result.name()).isEqualTo("My Playlist");
        mockServer.verify();
    }

    @Test
    void getPlaylistMetadata_shouldThrowResourceNotFound_on404() {
        mockServer.expect(requestTo("https://api.spotify.com/v1/playlists/notFound"))
                .andRespond(withStatus(org.springframework.http.HttpStatus.NOT_FOUND));

        assertThatThrownBy(() -> spotifyApiClient.getPlaylistMetadata("valid_token", "notFound"))
                .isInstanceOf(com.spotifyyoutube.migrator.common.exception.ResourceNotFoundException.class)
                .hasMessageContaining("Playlist not found");
    }

    @Test
    void getPlaylistTracks_shouldReturnPaging_onSuccess() throws Exception {
        String mockResponseBody = """
        {
            "href": "https://api.spotify.com/v1/playlists/p123/tracks",
            "items": [],
            "limit": 50,
            "offset": 0,
            "total": 0
        }
        """;

        mockServer.expect(requestTo("https://api.spotify.com/v1/playlists/p123/tracks?limit=50&offset=0"))
                .andExpect(method(HttpMethod.GET))
                .andExpect(header("Authorization", "Bearer valid_token"))
                .andRespond(withSuccess(mockResponseBody, MediaType.APPLICATION_JSON));

        SpotifyPagingDto<SpotifyPlaylistTrackDto> result = spotifyApiClient.getPlaylistTracks("valid_token", "p123", 50, 0);
        
        assertThat(result).isNotNull();
        assertThat(result.total()).isEqualTo(0);
        mockServer.verify();
    }
}
