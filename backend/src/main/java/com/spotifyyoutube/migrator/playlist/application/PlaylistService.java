package com.spotifyyoutube.migrator.playlist.application;

import com.spotifyyoutube.migrator.playlist.domain.Playlist;
import com.spotifyyoutube.migrator.playlist.domain.Track;

import java.util.List;
import java.util.UUID;

public interface PlaylistService {

    Playlist getPlaylistById(UUID id);

    List<Playlist> getUserPlaylists(UUID userId);

    List<Track> getTracksForPlaylist(UUID playlistId);
}
