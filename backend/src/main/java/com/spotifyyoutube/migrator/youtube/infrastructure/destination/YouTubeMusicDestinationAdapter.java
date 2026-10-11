package com.spotifyyoutube.migrator.youtube.infrastructure.destination;

import com.spotifyyoutube.migrator.migration.application.destination.MusicDestination;
import com.spotifyyoutube.migrator.migration.application.destination.model.AddTrackToDestinationPlaylistRequest;
import com.spotifyyoutube.migrator.migration.application.destination.model.CreateDestinationPlaylistRequest;
import com.spotifyyoutube.migrator.migration.application.destination.model.DestinationPlaylist;
import com.spotifyyoutube.migrator.migration.application.destination.model.DestinationPlaylistItem;
import com.spotifyyoutube.migrator.migration.application.destination.model.DestinationPlaylistVerification;
import com.spotifyyoutube.migrator.migration.application.destination.model.DestinationSearchResult;
import com.spotifyyoutube.migrator.migration.application.destination.model.DestinationTrackCandidate;
import com.spotifyyoutube.migrator.migration.application.destination.model.DestinationTrackDetails;
import com.spotifyyoutube.migrator.playlist.domain.Platform;
import com.spotifyyoutube.migrator.youtube.api.dto.AddVideoToPlaylistRequest;
import com.spotifyyoutube.migrator.youtube.api.dto.CreateYouTubePlaylistRequest;
import com.spotifyyoutube.migrator.youtube.api.dto.YouTubePlaylistItemResponse;
import com.spotifyyoutube.migrator.youtube.api.dto.YouTubePlaylistResponse;
import com.spotifyyoutube.migrator.youtube.api.dto.YouTubePrivacyStatus;
import com.spotifyyoutube.migrator.youtube.api.dto.YouTubeVideoDetailsResponse;
import com.spotifyyoutube.migrator.youtube.api.dto.YouTubeVideoSearchResponse;
import com.spotifyyoutube.migrator.youtube.api.dto.YouTubeVideoSummaryResponse;
import com.spotifyyoutube.migrator.youtube.application.YouTubePlaylistService;
import com.spotifyyoutube.migrator.youtube.application.YouTubeSearchService;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * YouTube implementation of the MusicDestination abstraction.
 * Adapts YouTube search and playlist services to the provider-neutral destination contract.
 */
@Component
public class YouTubeMusicDestinationAdapter implements MusicDestination {

    private final YouTubeSearchService searchService;
    private final YouTubePlaylistService playlistService;

    public YouTubeMusicDestinationAdapter(YouTubeSearchService searchService,
                                          YouTubePlaylistService playlistService) {
        this.searchService = searchService;
        this.playlistService = playlistService;
    }

    @Override
    public Platform getPlatform() {
        return Platform.YOUTUBE;
    }

    @Override
    public DestinationSearchResult searchTracks(String query, String pageToken, Integer limit) {
        YouTubeVideoSearchResponse response = searchService.searchVideos(query, pageToken, limit);
        List<DestinationTrackCandidate> candidates = new ArrayList<>();

        if (response != null && response.items() != null) {
            for (YouTubeVideoSummaryResponse item : response.items()) {
                candidates.add(new DestinationTrackCandidate(
                        item.videoId(),
                        item.title(),
                        item.channelTitle(),
                        item.description(),
                        item.thumbnailUrl(),
                        item.publishedAt()
                ));
            }
        }

        int totalResults = response != null ? response.totalResults() : 0;
        String nextPageToken = response != null ? response.nextPageToken() : null;

        return new DestinationSearchResult(candidates, nextPageToken, totalResults);
    }

    @Override
    public DestinationTrackDetails getTrackDetails(String trackId) {
        YouTubeVideoDetailsResponse response = searchService.getVideoDetails(trackId);
        if (response == null) {
            return null;
        }

        return new DestinationTrackDetails(
                response.videoId(),
                response.title(),
                response.channelTitle(),
                response.description(),
                response.thumbnailUrl(),
                response.publishedAt(),
                response.duration()
        );
    }

    @Override
    public DestinationPlaylist createPlaylist(UUID userId, CreateDestinationPlaylistRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("Playlist request must not be null");
        }

        YouTubePrivacyStatus privacy = request.isPrivate()
                ? YouTubePrivacyStatus.PRIVATE
                : YouTubePrivacyStatus.PUBLIC;

        CreateYouTubePlaylistRequest createRequest = new CreateYouTubePlaylistRequest(
                request.title(),
                request.description(),
                privacy
        );

        YouTubePlaylistResponse response = playlistService.createPlaylist(userId, createRequest);
        return toDestinationPlaylist(response);
    }

    @Override
    public DestinationPlaylistItem addTrackToPlaylist(UUID userId, String playlistId, AddTrackToDestinationPlaylistRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("Add track request must not be null");
        }

        AddVideoToPlaylistRequest addRequest = new AddVideoToPlaylistRequest(
                request.trackId(),
                request.position()
        );

        YouTubePlaylistItemResponse response = playlistService.addVideoToPlaylist(userId, playlistId, addRequest);
        if (response == null) {
            return null;
        }

        return new DestinationPlaylistItem(
                response.playlistItemId(),
                response.playlistId(),
                response.videoId(),
                response.position()
        );
    }

    @Override
    public DestinationPlaylist getPlaylist(UUID userId, String playlistId) {
        YouTubePlaylistResponse response = playlistService.getPlaylist(userId, playlistId);
        return toDestinationPlaylist(response);
    }

    @Override
    public DestinationPlaylistVerification verifyPlaylist(UUID userId, String playlistId) {
        return playlistService.verifyPlaylist(userId, playlistId);
    }

    private DestinationPlaylist toDestinationPlaylist(YouTubePlaylistResponse response) {
        if (response == null) {
            return null;
        }

        String url = response.id() != null ? "https://www.youtube.com/playlist?list=" + response.id() : null;
        return new DestinationPlaylist(
                response.id(),
                response.title(),
                response.description(),
                response.privacyStatus(),
                url,
                Platform.YOUTUBE
        );
    }
}
