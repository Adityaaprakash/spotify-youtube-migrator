package com.spotifyyoutube.migrator.playlist.api;

import com.spotifyyoutube.migrator.common.config.SecurityConfig;
import com.spotifyyoutube.migrator.common.exception.GlobalExceptionHandler;
import com.spotifyyoutube.migrator.common.exception.ResourceNotFoundException;
import com.spotifyyoutube.migrator.playlist.application.PlaylistService;
import com.spotifyyoutube.migrator.playlist.domain.Platform;
import com.spotifyyoutube.migrator.playlist.domain.Playlist;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.UUID;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.springframework.security.test.context.support.WithMockUser;

@WebMvcTest(PlaylistController.class)
@Import({SecurityConfig.class, GlobalExceptionHandler.class})
@WithMockUser
public class PlaylistControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private PlaylistService playlistService;

    @Test
    public void testGetPlaylists_Success() throws Exception {
        UUID userId = UUID.randomUUID();
        Playlist playlist = new Playlist();
        playlist.setName("My Playlist");
        playlist.setPlatform(Platform.SPOTIFY);
        playlist.setExternalId("ext-id");

        when(playlistService.getUserPlaylists(userId)).thenReturn(List.of(playlist));

        mockMvc.perform(get("/api/playlists")
                .param("userId", userId.toString())
                .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].name").value("My Playlist"))
                .andExpect(jsonPath("$[0].platform").value("SPOTIFY"));
    }

    @Test
    public void testGetPlaylists_MissingUserId() throws Exception {
        mockMvc.perform(get("/api/playlists")
                .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isBadRequest()); // Missing parameter
    }

    @Test
    public void testGetPlaylist_Success() throws Exception {
        UUID id = UUID.randomUUID();
        Playlist playlist = new Playlist();
        playlist.setId(id);
        playlist.setName("Test");
        playlist.setPlatform(Platform.YOUTUBE);
        playlist.setExternalId("some-id");

        when(playlistService.getPlaylistById(id)).thenReturn(playlist);

        mockMvc.perform(get("/api/playlists/" + id)
                .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Test"))
                .andExpect(jsonPath("$.id").value(id.toString()));
    }

    @Test
    public void testGetPlaylist_NotFound() throws Exception {
        UUID id = UUID.randomUUID();
        when(playlistService.getPlaylistById(id))
                .thenThrow(new ResourceNotFoundException("Playlist not found"));

        mockMvc.perform(get("/api/playlists/" + id)
                .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }
}
