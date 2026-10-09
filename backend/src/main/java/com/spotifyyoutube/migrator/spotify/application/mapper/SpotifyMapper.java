package com.spotifyyoutube.migrator.spotify.application.mapper;

import com.spotifyyoutube.migrator.spotify.api.dto.SpotifyPageResponse;
import com.spotifyyoutube.migrator.spotify.api.dto.SpotifyPlaylistSummaryResponse;
import com.spotifyyoutube.migrator.spotify.api.dto.SpotifyProfileResponse;
import com.spotifyyoutube.migrator.spotify.infrastructure.dto.SpotifyImageDto;
import com.spotifyyoutube.migrator.spotify.infrastructure.dto.SpotifyPagingDto;
import com.spotifyyoutube.migrator.spotify.infrastructure.dto.SpotifyPlaylistSummaryDto;
import com.spotifyyoutube.migrator.spotify.infrastructure.dto.SpotifyUserProfileDto;
import org.springframework.stereotype.Component;

import java.util.List;

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
}
