package com.spotifyyoutube.migrator.playlist.api.mapper;

import com.spotifyyoutube.migrator.playlist.api.dto.PlaylistResponse;
import com.spotifyyoutube.migrator.playlist.api.dto.TrackResponse;
import com.spotifyyoutube.migrator.playlist.domain.Playlist;
import com.spotifyyoutube.migrator.playlist.domain.Track;

import java.util.List;
import java.util.stream.Collectors;

public class PlaylistMapper {

    private PlaylistMapper() {
        // Utility class
    }

    public static PlaylistResponse toResponse(Playlist playlist) {
        if (playlist == null) {
            return null;
        }
        return new PlaylistResponse(
                playlist.getId(),
                playlist.getName(),
                playlist.getDescription(),
                playlist.getPlatform(),
                playlist.getExternalId(),
                playlist.getUrl(),
                playlist.getTotalTracks()
        );
    }

    public static List<PlaylistResponse> toResponseList(List<Playlist> playlists) {
        if (playlists == null) {
            return List.of();
        }
        return playlists.stream()
                .map(PlaylistMapper::toResponse)
                .collect(Collectors.toList());
    }

    public static TrackResponse toTrackResponse(Track track) {
        if (track == null) {
            return null;
        }
        return new TrackResponse(
                track.getId(),
                track.getName(),
                track.getArtist(),
                track.getAlbum(),
                track.getDurationMs(),
                track.getExternalId()
        );
    }

    public static List<TrackResponse> toTrackResponseList(List<Track> tracks) {
        if (tracks == null) {
            return List.of();
        }
        return tracks.stream()
                .map(PlaylistMapper::toTrackResponse)
                .collect(Collectors.toList());
    }
}
