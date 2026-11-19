package com.spotifyyoutube.migrator.playlist.api;

import com.spotifyyoutube.migrator.common.config.SecurityConfig;
import com.spotifyyoutube.migrator.common.exception.GlobalExceptionHandler;
import com.spotifyyoutube.migrator.common.exception.ResourceNotFoundException;
import com.spotifyyoutube.migrator.identity.domain.User;
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
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
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

    @Test
    public void testGetPlaylists_InvalidUserId_ReturnsBadRequest() throws Exception {
        mockMvc.perform(get("/api/playlists?userId=not-a-valid-uuid")
                .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
                .andExpect(jsonPath("$.message").value("Invalid parameter value for: userId"));
    }

    @Test
    public void testGetPlaylist_InvalidId_ReturnsBadRequest() throws Exception {
        mockMvc.perform(get("/api/playlists/not-a-valid-uuid")
                .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
                .andExpect(jsonPath("$.message").value("Invalid parameter value for: id"));
    }

    @Test
    public void testGetPlaylists_EmptyList_ReturnsEmptyJsonArray() throws Exception {
        UUID userId = UUID.randomUUID();
        when(playlistService.getUserPlaylists(userId)).thenReturn(List.of());

        mockMvc.perform(get("/api/playlists?userId=" + userId)
                .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray())
                .andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    @WithMockUser(username = "attacker@example.com")
    public void testGetPlaylist_CrossUserAccess_ReturnsNotFoundForIdorProtection() throws Exception {
        UUID id = UUID.randomUUID();
        User victim = new User("victim@example.com");
        victim.setId(UUID.randomUUID());

        Playlist playlist = new Playlist();
        playlist.setId(id);
        playlist.setName("Private Playlist");
        playlist.setPlatform(Platform.SPOTIFY);
        playlist.setUser(victim);

        when(playlistService.getPlaylistById(id)).thenReturn(playlist);

        mockMvc.perform(get("/api/playlists/" + id)
                .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"))
                .andExpect(jsonPath("$.message").value("Playlist not found with id: " + id));
    }

    @Test
    public void testGetPlaylists_PostMethod_ReturnsMethodNotAllowed() throws Exception {
        mockMvc.perform(post("/api/playlists")
                        .with(csrf()))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(jsonPath("$.code").value("METHOD_NOT_ALLOWED"));
    }

    @Test
    public void testGetPlaylist_DeleteMethod_ReturnsMethodNotAllowed() throws Exception {
        UUID id = UUID.randomUUID();
        mockMvc.perform(delete("/api/playlists/" + id)
                        .with(csrf()))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(jsonPath("$.code").value("METHOD_NOT_ALLOWED"));
    }
}
