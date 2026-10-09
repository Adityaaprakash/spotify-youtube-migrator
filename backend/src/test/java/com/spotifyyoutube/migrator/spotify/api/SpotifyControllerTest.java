package com.spotifyyoutube.migrator.spotify.api;

import com.spotifyyoutube.migrator.identity.application.UserService;
import com.spotifyyoutube.migrator.identity.domain.User;
import com.spotifyyoutube.migrator.spotify.api.dto.SpotifyPageResponse;
import com.spotifyyoutube.migrator.spotify.api.dto.SpotifyPlaylistSummaryResponse;
import com.spotifyyoutube.migrator.spotify.api.dto.SpotifyProfileResponse;
import com.spotifyyoutube.migrator.playlist.api.dto.PlaylistResponse;
import com.spotifyyoutube.migrator.playlist.api.dto.TrackResponse;
import com.spotifyyoutube.migrator.playlist.domain.Platform;
import com.spotifyyoutube.migrator.spotify.application.SpotifyIdentityService;
import com.spotifyyoutube.migrator.spotify.application.SpotifyPlaylistService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.UUID;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(SpotifyController.class)
@org.springframework.context.annotation.Import(com.spotifyyoutube.migrator.common.config.SecurityConfig.class)
class SpotifyControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private SpotifyIdentityService identityService;

    @MockBean
    private SpotifyPlaylistService playlistService;

    @MockBean
    private UserService userService;

    @Test
    @WithMockUser(username = "test@example.com")
    void getCurrentProfile_shouldReturnProfile_whenAuthenticated() throws Exception {
        User user = new User();
        user.setId(UUID.randomUUID());
        user.setEmail("test@example.com");

        when(userService.getUserByEmail("test@example.com")).thenReturn(user);
        when(identityService.getConnectedProfile(user.getId())).thenReturn(
                new SpotifyProfileResponse("u1", "Disp", "test@example.com", null)
        );

        mockMvc.perform(get("/api/spotify/me"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value("u1"))
                .andExpect(jsonPath("$.displayName").value("Disp"));
    }

    @Test
    void getCurrentProfile_shouldReturn403_whenUnauthenticated() throws Exception {
        mockMvc.perform(get("/api/spotify/me"))
                .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(username = "test@example.com")
    void getPlaylists_shouldReturnPlaylists_whenAuthenticated() throws Exception {
        User user = new User();
        user.setId(UUID.randomUUID());
        user.setEmail("test@example.com");

        when(userService.getUserByEmail("test@example.com")).thenReturn(user);
        when(playlistService.getPlaylists(user.getId(), 20, 0)).thenReturn(
                new SpotifyPageResponse<>(List.of(), 0, 20, 0, false)
        );

        mockMvc.perform(get("/api/spotify/playlists?limit=20&offset=0"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.limit").value(20));
    }

    @Test
    @WithMockUser(username = "test@example.com")
    void getPlaylistMetadata_shouldReturnResponse() throws Exception {
        User user = new User();
        user.setId(UUID.randomUUID());
        user.setEmail("test@example.com");

        when(userService.getUserByEmail("test@example.com")).thenReturn(user);

        PlaylistResponse mockResponse = new PlaylistResponse(
                null, "Playlist 1", "Desc", Platform.SPOTIFY, "pl1", "url", 10
        );
        when(playlistService.getPlaylistMetadata(user.getId(), "pl1")).thenReturn(mockResponse);

        mockMvc.perform(get("/api/spotify/playlists/pl1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.externalId").value("pl1"))
                .andExpect(jsonPath("$.name").value("Playlist 1"));
    }

    @Test
    @WithMockUser(username = "test@example.com")
    void getPlaylistTracks_shouldReturnTrackList() throws Exception {
        User user = new User();
        user.setId(UUID.randomUUID());
        user.setEmail("test@example.com");

        when(userService.getUserByEmail("test@example.com")).thenReturn(user);

        TrackResponse t1 = new TrackResponse(null, "Song", "Art", "Alb", 123, "t1");
        when(playlistService.getPlaylistTracks(user.getId(), "pl1")).thenReturn(List.of(t1));

        mockMvc.perform(get("/api/spotify/playlists/pl1/tracks"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].externalId").value("t1"));
    }
}
