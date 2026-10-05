package com.spotifyyoutube.migrator.playlist.application;

import com.spotifyyoutube.migrator.common.exception.ResourceNotFoundException;
import com.spotifyyoutube.migrator.playlist.domain.Playlist;
import com.spotifyyoutube.migrator.playlist.domain.Track;
import com.spotifyyoutube.migrator.playlist.repository.PlaylistRepository;
import com.spotifyyoutube.migrator.playlist.repository.TrackRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Service
@Transactional(readOnly = true)
public class PlaylistServiceImpl implements PlaylistService {

    private final PlaylistRepository playlistRepository;
    private final TrackRepository trackRepository;

    public PlaylistServiceImpl(PlaylistRepository playlistRepository, TrackRepository trackRepository) {
        this.playlistRepository = playlistRepository;
        this.trackRepository = trackRepository;
    }

    @Override
    public Playlist getPlaylistById(UUID id) {
        return playlistRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Playlist not found with id: " + id));
    }

    @Override
    public List<Playlist> getUserPlaylists(UUID userId) {
        return playlistRepository.findByUserId(userId);
    }

    @Override
    public List<Track> getTracksForPlaylist(UUID playlistId) {
        // Enforce playlist existence validation implicitly if required,
        // but here we can just list tracks by ID.
        if (!playlistRepository.existsById(playlistId)) {
            throw new ResourceNotFoundException("Playlist not found with id: " + playlistId);
        }
        return trackRepository.findByPlaylistId(playlistId);
    }
}
