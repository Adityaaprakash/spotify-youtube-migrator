package com.spotifyyoutube.migrator.migration.application.destination;

import com.spotifyyoutube.migrator.migration.application.destination.model.AddTrackToDestinationPlaylistRequest;
import com.spotifyyoutube.migrator.migration.application.destination.model.CreateDestinationPlaylistRequest;
import com.spotifyyoutube.migrator.migration.application.destination.model.DestinationPlaylist;
import com.spotifyyoutube.migrator.migration.application.destination.model.DestinationPlaylistItem;
import com.spotifyyoutube.migrator.migration.application.destination.model.DestinationPlaylistVerification;
import com.spotifyyoutube.migrator.migration.application.destination.model.DestinationSearchResult;
import com.spotifyyoutube.migrator.migration.application.destination.model.DestinationTrackDetails;
import com.spotifyyoutube.migrator.playlist.domain.Platform;

import java.util.UUID;

/**
 * Provider-neutral abstraction representing a music destination.
 * Allows migration orchestration and application services to perform candidate discovery,
 * track inspection, playlist creation, playlist insertion, and destination verification
 * without coupling to concrete provider APIs.
 */
public interface MusicDestination {

    /**
     * Identifies the platform of this music destination.
     *
     * @return platform enum
     */
    Platform getPlatform();

    /**
     * Searches for track/video candidates matching the specified query.
     *
     * @param query non-blank search string
     * @param pageToken optional pagination token
     * @param limit maximum results to return
     * @return provider-neutral search result
     */
    DestinationSearchResult searchTracks(String query, String pageToken, Integer limit);

    /**
     * Retrieves detailed metadata for a specific track/video.
     *
     * @param trackId destination-specific track/video identifier
     * @return detailed track metadata
     */
    DestinationTrackDetails getTrackDetails(String trackId);

    /**
     * Creates a new playlist at the destination under the authenticated user's credentials.
     *
     * @param userId user identifier possessing the destination authorization
     * @param request playlist creation parameters
     * @return created destination playlist
     */
    DestinationPlaylist createPlaylist(UUID userId, CreateDestinationPlaylistRequest request);

    /**
     * Inserts a track/video into an existing destination playlist.
     *
     * @param userId user identifier possessing the destination authorization
     * @param playlistId destination playlist identifier
     * @param request track insertion parameters
     * @return created destination playlist item
     */
    DestinationPlaylistItem addTrackToPlaylist(UUID userId, String playlistId, AddTrackToDestinationPlaylistRequest request);

    /**
     * Retrieves playlist details from the destination.
     *
     * @param userId user identifier possessing the destination authorization
     * @param playlistId destination playlist identifier
     * @return destination playlist details
     */
    DestinationPlaylist getPlaylist(UUID userId, String playlistId);

    /**
     * Verifies that a destination playlist exists and is accessible.
     * Distinguishes ACCESSIBLE, NOT_FOUND, INACCESSIBLE, FAILED, and UNCERTAIN states.
     *
     * @param userId user identifier possessing the destination authorization
     * @param playlistId destination playlist identifier
     * @return verification result with status and diagnostic details
     */
    DestinationPlaylistVerification verifyPlaylist(UUID userId, String playlistId);
}
