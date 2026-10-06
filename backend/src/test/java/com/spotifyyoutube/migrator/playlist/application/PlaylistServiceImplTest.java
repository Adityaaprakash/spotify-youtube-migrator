package com.spotifyyoutube.migrator.playlist.application;

import com.spotifyyoutube.migrator.common.exception.ResourceNotFoundException;
import com.spotifyyoutube.migrator.playlist.domain.Playlist;
import com.spotifyyoutube.migrator.playlist.domain.Track;
import com.spotifyyoutube.migrator.playlist.repository.PlaylistRepository;
import com.spotifyyoutube.migrator.playlist.repository.TrackRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
public class PlaylistServiceImplTest {

    @Mock
    private PlaylistRepository playlistRepository;

    @Mock
    private TrackRepository trackRepository;

    @InjectMocks
    private PlaylistServiceImpl playlistService;

    @Test
    public void testGetPlaylistById_Success() {
        UUID id = UUID.randomUUID();
        Playlist p = new Playlist();
        when(playlistRepository.findById(id)).thenReturn(Optional.of(p));

        Playlist result = playlistService.getPlaylistById(id);
        assertNotNull(result);
    }

    @Test
    public void testGetPlaylistById_NotFound() {
        UUID id = UUID.randomUUID();
        when(playlistRepository.findById(id)).thenReturn(Optional.empty());

        assertThrows(ResourceNotFoundException.class, () -> playlistService.getPlaylistById(id));
    }

    @Test
    public void testGetUserPlaylists() {
        UUID userId = UUID.randomUUID();
        Playlist p = new Playlist();
        when(playlistRepository.findByUserId(userId)).thenReturn(List.of(p));

        List<Playlist> result = playlistService.getUserPlaylists(userId);
        assertEquals(1, result.size());
    }

    @Test
    public void testGetTracksForPlaylist_Success() {
        UUID playlistId = UUID.randomUUID();
        when(playlistRepository.existsById(playlistId)).thenReturn(true);
        when(trackRepository.findByPlaylistId(playlistId)).thenReturn(List.of(new Track()));

        List<Track> result = playlistService.getTracksForPlaylist(playlistId);
        assertEquals(1, result.size());
    }

    @Test
    public void testGetTracksForPlaylist_PlaylistNotFound() {
        UUID playlistId = UUID.randomUUID();
        when(playlistRepository.existsById(playlistId)).thenReturn(false);

        assertThrows(ResourceNotFoundException.class, () -> playlistService.getTracksForPlaylist(playlistId));
    }
}
