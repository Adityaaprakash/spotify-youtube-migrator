package com.spotifyyoutube.migrator.spotify.application.mapper;

import com.spotifyyoutube.migrator.spotify.api.dto.SpotifyPageResponse;
import com.spotifyyoutube.migrator.spotify.api.dto.SpotifyPlaylistSummaryResponse;
import com.spotifyyoutube.migrator.spotify.api.dto.SpotifyProfileResponse;
import com.spotifyyoutube.migrator.spotify.infrastructure.dto.SpotifyPagingDto;
import com.spotifyyoutube.migrator.spotify.infrastructure.dto.SpotifyPlaylistSummaryDto;
import com.spotifyyoutube.migrator.spotify.infrastructure.dto.SpotifyUserProfileDto;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.stream.Collectors;

import com.spotifyyoutube.migrator.identity.domain.User;
import com.spotifyyoutube.migrator.playlist.domain.Platform;
import com.spotifyyoutube.migrator.playlist.domain.Playlist;
import com.spotifyyoutube.migrator.playlist.domain.Track;
import com.spotifyyoutube.migrator.spotify.infrastructure.dto.SpotifyArtistDto;
import com.spotifyyoutube.migrator.spotify.infrastructure.dto.SpotifyTrackDto;

@Component
public class SpotifyMapper {

    public SpotifyProfileResponse mapProfile(SpotifyUserProfileDto profileDto) {
        String imageUrl = null;
        if (profileDto.images() != null && !profileDto.images().isEmpty()) {
            imageUrl = profileDto.images().getFirst().url();
        }
        return new SpotifyProfileResponse(
                profileDto.id(),
                profileDto.displayName(),
                profileDto.email(),
                imageUrl
        );
    }

    public SpotifyPlaylistSummaryResponse mapPlaylistSummary(SpotifyPlaylistSummaryDto playlistDto) {
        String imageUrl = null;
        if (playlistDto.images() != null && !playlistDto.images().isEmpty()) {
            imageUrl = playlistDto.images().getFirst().url();
        }

        String ownerName = null;
        if (playlistDto.owner() != null) {
            ownerName = playlistDto.owner().displayName() != null ? playlistDto.owner().displayName() : playlistDto.owner().id();
        }

        Integer tracksCount = null;
        if (playlistDto.tracks() != null) {
            tracksCount = playlistDto.tracks().total();
        }

        String spotifyUrl = null;
        if (playlistDto.externalUrls() != null) {
            spotifyUrl = playlistDto.externalUrls().spotify();
        }

        return new SpotifyPlaylistSummaryResponse(
                playlistDto.id(),
                playlistDto.name(),
                playlistDto.description(),
                ownerName,
                imageUrl,
                tracksCount,
                spotifyUrl
        );
    }

    public <T, R> SpotifyPageResponse<R> mapPaging(SpotifyPagingDto<T> pagingDto, List<R> mappedItems) {
        boolean hasNext = pagingDto.next() != null && !pagingDto.next().isBlank();
        return new SpotifyPageResponse<>(
                mappedItems,
                pagingDto.total(),
                pagingDto.limit(),
                pagingDto.offset(),
                hasNext
        );
    }

    public Playlist toPlaylistDomain(SpotifyPlaylistSummaryDto dto, User user) {
        if (dto == null) return null;

        Playlist playlist = new Playlist();
        playlist.setPlatform(Platform.SPOTIFY);
        playlist.setExternalId(dto.id());
        playlist.setName(dto.name() != null ? dto.name() : "Unknown Playlist");
        playlist.setDescription(dto.description());

        if (dto.externalUrls() != null) {
            playlist.setUrl(dto.externalUrls().spotify());
        }

        if (dto.images() != null && !dto.images().isEmpty()) {
            playlist.setImageUrl(dto.images().getFirst().url());
        }

        if (dto.tracks() != null) {
            playlist.setTotalTracks(dto.tracks().total());
        }

        playlist.setUser(user);
        return playlist;
    }

    public Track toTrackDomain(SpotifyTrackDto dto, Playlist playlist) {
        if (dto == null) return null;

        Track track = new Track();
        track.setExternalId(dto.id() != null ? dto.id() : "local-" + java.util.UUID.randomUUID().toString());
        track.setName(dto.name() != null ? dto.name() : "Unknown Track");
        
        String artistNames = "Unknown Artist";
        if (dto.artists() != null && !dto.artists().isEmpty()) {
            String joined = dto.artists().stream()
                    .map(SpotifyArtistDto::name)
                    .filter(name -> name != null && !name.isBlank())
                    .collect(Collectors.joining(", "));
            artistNames = joined.isBlank() ? "Unknown Artist" : joined;
        }
        track.setArtist(artistNames);

        if (dto.album() != null) {
            track.setAlbum(dto.album().name());
        }

        track.setDurationMs(dto.durationMs() != null ? dto.durationMs() : 0);
        track.setPlaylist(playlist);
        
        return track;
    }
}
