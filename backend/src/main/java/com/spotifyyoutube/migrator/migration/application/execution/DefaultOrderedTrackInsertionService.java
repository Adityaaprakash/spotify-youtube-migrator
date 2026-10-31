package com.spotifyyoutube.migrator.migration.application.execution;

import com.spotifyyoutube.migrator.common.exception.ExternalProviderException;
import com.spotifyyoutube.migrator.common.exception.InvalidStateException;
import com.spotifyyoutube.migrator.common.exception.ResourceNotFoundException;
import com.spotifyyoutube.migrator.migration.application.destination.MusicDestination;
import com.spotifyyoutube.migrator.migration.application.destination.model.AddTrackToDestinationPlaylistRequest;
import com.spotifyyoutube.migrator.migration.application.destination.model.DestinationPlaylistItem;
import com.spotifyyoutube.migrator.migration.application.execution.contract.ApprovedMigrationContext;
import com.spotifyyoutube.migrator.migration.application.execution.contract.ApprovedTrack;
import com.spotifyyoutube.migrator.migration.application.execution.contract.InsertTracksExecutionRequest;
import com.spotifyyoutube.migrator.migration.application.execution.contract.PlaylistTrackInsertionSummary;
import com.spotifyyoutube.migrator.migration.application.execution.contract.TrackInsertionOutcome;
import com.spotifyyoutube.migrator.playlist.domain.Platform;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.ResourceAccessException;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Default implementation of {@link OrderedTrackInsertionService}.
 * <p>
 * Coordinates sequential, ordered insertion of approved destination tracks into a destination playlist:
 * <ol>
 *   <li>Validates the execution context through {@link ApprovedMigrationExecutionService}.</li>
 *   <li>Enforces anti-impersonation, anti-IDOR, and non-empty approved track selection guards.</li>
 *   <li>Verifies destination playlist existence and accessibility via {@link MusicDestination#getPlaylist}.</li>
 *   <li>Iterates sequentially through approved tracks in ascending target position sequence.</li>
 *   <li>Records granular per-track outcomes (SUCCESS, FAILED, UNCERTAIN, SKIPPED).</li>
 *   <li>Enforces conservative failure continuation: stops insertions on failure/timeout to preserve ordering.</li>
 *   <li>Assembles and returns a comprehensive {@link PlaylistTrackInsertionSummary}.</li>
 * </ol>
 */
@Service
public class DefaultOrderedTrackInsertionService implements OrderedTrackInsertionService {

    private static final Logger log = LoggerFactory.getLogger(DefaultOrderedTrackInsertionService.class);

    private static final Pattern TOKEN_PATTERN = Pattern.compile(
            "(?i)(bearer\\s+|(?:access_token|refresh_token|token|api_key|apikey|key|client_secret|secret|password)=)[^\\s&\"',;]+"
    );

    private final ApprovedMigrationExecutionService executionService;
    private final MusicDestination musicDestination;

    public DefaultOrderedTrackInsertionService(
            ApprovedMigrationExecutionService executionService,
            MusicDestination musicDestination
    ) {
        this.executionService = Objects.requireNonNull(executionService, "ApprovedMigrationExecutionService must not be null");
        this.musicDestination = Objects.requireNonNull(musicDestination, "MusicDestination must not be null");
    }

    @Override
    public PlaylistTrackInsertionSummary insertApprovedTracks(
            InsertTracksExecutionRequest request,
            UUID authenticatedUserId
    ) {
        if (request == null) {
            throw new IllegalArgumentException("Execution request must not be null");
        }
        if (authenticatedUserId == null) {
            throw new IllegalArgumentException("Authenticated user ID must not be null");
        }

        // Anti-impersonation: verify caller-supplied userId matches authenticated principal
        if (request.getUserId().isPresent()) {
            UUID requestedUserId = request.getUserId().get();
            if (!Objects.equals(requestedUserId, authenticatedUserId)) {
                throw new IllegalArgumentException(String.format(
                        "Requested user ID %s does not match authenticated user %s",
                        requestedUserId, authenticatedUserId
                ));
            }
        }

        String playlistId = request.destinationPlaylistId();
        if (playlistId == null || playlistId.isBlank()) {
            throw new IllegalArgumentException("Destination playlist ID must not be blank");
        }
        String trimmedPlaylistId = playlistId.trim();

        // 1. Resolve and validate the server-side approved migration context
        // Enforces session existence, anti-IDOR ownership (404), terminal COMPLETED status (409),
        // and all 12 domain eligibility rules
        ApprovedMigrationContext context = executionService.prepareExecution(request.reviewSessionId(), authenticatedUserId);

        // 2. Guard against empty approved selection (zero destination mutation permitted)
        if (!context.hasApprovedTracks()) {
            throw new InvalidStateException(String.format(
                    "Cannot insert tracks: approved track selection is empty for review session %s",
                    context.reviewSessionId()
            ));
        }

        // 3. Verify destination playlist existence and accessibility under authenticated user's credentials
        try {
            musicDestination.getPlaylist(authenticatedUserId, trimmedPlaylistId);
        } catch (ResourceNotFoundException ex) {
            log.warn("Destination playlist {} not found or inaccessible for user {}", trimmedPlaylistId, authenticatedUserId);
            throw new ResourceNotFoundException("Destination playlist not found or inaccessible: " + trimmedPlaylistId);
        } catch (ExternalProviderException ex) {
            log.error("Provider error checking playlist {}: {}", trimmedPlaylistId, sanitize(ex.getMessage()));
            throw ex;
        }

        Instant startedAt = Instant.now();
        List<ApprovedTrack> approvedTracks = context.approvedTracks();
        List<TrackInsertionOutcome> outcomes = new ArrayList<>(approvedTracks.size());

        int successfulCount = 0;
        int failedCount = 0;
        int uncertainCount = 0;
        int skippedCount = 0;

        boolean stopRemaining = false;
        String stopReason = null;

        // 4. Sequential Insertion in Strict Target Position Sequence
        for (int i = 0; i < approvedTracks.size(); i++) {
            ApprovedTrack track = approvedTracks.get(i);

            if (stopRemaining) {
                outcomes.add(TrackInsertionOutcome.skipped(
                        track,
                        "Skipped due to prior insertion " + stopReason
                ));
                skippedCount++;
                continue;
            }

            try {
                AddTrackToDestinationPlaylistRequest addRequest = new AddTrackToDestinationPlaylistRequest(
                        track.destinationTrackId(),
                        successfulCount // explicit continuous destination index
                );

                DestinationPlaylistItem item = musicDestination.addTrackToPlaylist(
                        authenticatedUserId,
                        trimmedPlaylistId,
                        addRequest
                );

                if (item != null && item.id() != null && !item.id().isBlank()) {
                    outcomes.add(TrackInsertionOutcome.success(track, item.id().trim()));
                    successfulCount++;
                } else {
                    outcomes.add(TrackInsertionOutcome.failed(
                            track,
                            "Provider returned null or missing playlist item ID"
                    ));
                    failedCount++;
                    stopRemaining = true;
                    stopReason = "failure";
                }
            } catch (ResourceAccessException ex) {
                // Ambiguous failure: network timeout / connection reset
                String sanitizedMsg = sanitize(ex.getMessage());
                log.warn("Insertion timed out for track {} in playlist {}. Remote outcome is UNCERTAIN: {}",
                        track.destinationTrackId(), trimmedPlaylistId, sanitizedMsg);
                outcomes.add(TrackInsertionOutcome.uncertain(
                        track,
                        "Insertion timed out; remote outcome is uncertain: " + sanitizedMsg
                ));
                uncertainCount++;
                stopRemaining = true;
                stopReason = "timeout (uncertain outcome)";
            } catch (ExternalProviderException ex) {
                String sanitizedMsg = sanitize(ex.getMessage());
                log.error("Destination provider rejected track insertion for track {} in playlist {}: {}",
                        track.destinationTrackId(), trimmedPlaylistId, sanitizedMsg);
                outcomes.add(TrackInsertionOutcome.failed(track, sanitizedMsg));
                failedCount++;
                stopRemaining = true;
                stopReason = "provider rejection";
            } catch (Exception ex) {
                String sanitizedMsg = sanitize(ex.getMessage());
                log.error("Unexpected error inserting track {} in playlist {}: {}",
                        track.destinationTrackId(), trimmedPlaylistId, sanitizedMsg);
                outcomes.add(TrackInsertionOutcome.failed(track, "Unexpected insertion error: " + sanitizedMsg));
                failedCount++;
                stopRemaining = true;
                stopReason = "unexpected error";
            }
        }

        Instant completedAt = Instant.now();
        Platform platform = musicDestination.getPlatform() != null
                ? musicDestination.getPlatform()
                : Platform.YOUTUBE;

        log.info("Finished track insertion for session {} into playlist {}: {} succeeded, {} failed, {} uncertain, {} skipped out of {} approved tracks",
                context.reviewSessionId(), trimmedPlaylistId, successfulCount, failedCount, uncertainCount, skippedCount, approvedTracks.size());

        return new PlaylistTrackInsertionSummary(
                context.reviewSessionId(),
                context.planId(),
                authenticatedUserId,
                platform,
                trimmedPlaylistId,
                approvedTracks.size(),
                successfulCount,
                failedCount,
                uncertainCount,
                skippedCount,
                outcomes,
                startedAt,
                completedAt
        );
    }

    @Override
    public PlaylistTrackInsertionSummary insertApprovedTracks(
            UUID reviewSessionId,
            String destinationPlaylistId,
            UUID authenticatedUserId
    ) {
        if (reviewSessionId == null) {
            throw new IllegalArgumentException("Review session ID must not be null");
        }
        InsertTracksExecutionRequest req = InsertTracksExecutionRequest.of(reviewSessionId, destinationPlaylistId, authenticatedUserId);
        return insertApprovedTracks(req, authenticatedUserId);
    }

    private String sanitize(String message) {
        if (message == null) {
            return "null";
        }
        return TOKEN_PATTERN.matcher(message).replaceAll("$1[REDACTED]");
    }
}
