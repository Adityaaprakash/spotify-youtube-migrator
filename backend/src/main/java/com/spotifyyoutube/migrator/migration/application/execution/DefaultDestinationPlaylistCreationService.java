package com.spotifyyoutube.migrator.migration.application.execution;

import com.spotifyyoutube.migrator.common.exception.ExternalProviderException;
import com.spotifyyoutube.migrator.common.exception.InvalidStateException;
import com.spotifyyoutube.migrator.migration.application.destination.MusicDestination;
import com.spotifyyoutube.migrator.migration.application.destination.model.CreateDestinationPlaylistRequest;
import com.spotifyyoutube.migrator.migration.application.destination.model.DestinationPlaylist;
import com.spotifyyoutube.migrator.migration.application.execution.contract.ApprovedMigrationContext;
import com.spotifyyoutube.migrator.migration.application.execution.contract.ApprovedMigrationExecutionRequest;
import com.spotifyyoutube.migrator.migration.application.execution.contract.DestinationPlaylistCreationResult;
import com.spotifyyoutube.migrator.playlist.domain.Platform;
import com.spotifyyoutube.migrator.youtube.application.YouTubePlaylistService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClientException;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Default implementation of {@link DestinationPlaylistCreationService}.
 * <p>
 * Coordinates destination playlist creation (Phase 11C) by:
 * <ol>
 *   <li>Validating the execution context through {@link ApprovedMigrationExecutionService}.</li>
 *   <li>Enforcing user identity, ownership, and non-empty approved track selection guards.</li>
 *   <li>Validating playlist metadata length and format bounds.</li>
 *   <li>Invoking {@link MusicDestination#createPlaylist(UUID, CreateDestinationPlaylistRequest)}.</li>
 *   <li>Validating provider response identity and sanitizing diagnostic error messages.</li>
 *   <li>Strictly performing ZERO track insertions (deferred to Phase 11D).</li>
 * </ol>
 */
@Service
public class DefaultDestinationPlaylistCreationService implements DestinationPlaylistCreationService {

    private static final Logger log = LoggerFactory.getLogger(DefaultDestinationPlaylistCreationService.class);

    private static final Pattern TOKEN_PATTERN = Pattern.compile(
            "(?i)(bearer\\s+|(?:access_token|refresh_token|token|api_key|apikey|key|client_secret|secret|password)=)[^\\s&\"',;]+"
    );

    private final ApprovedMigrationExecutionService executionService;
    private final MusicDestination musicDestination;

    public DefaultDestinationPlaylistCreationService(
            ApprovedMigrationExecutionService executionService,
            MusicDestination musicDestination
    ) {
        this.executionService = Objects.requireNonNull(executionService, "ApprovedMigrationExecutionService must not be null");
        this.musicDestination = Objects.requireNonNull(musicDestination, "MusicDestination must not be null");
    }

    @Override
    public DestinationPlaylistCreationResult createDestinationPlaylist(
            ApprovedMigrationExecutionRequest request,
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

        // 1. Resolve and validate the server-side approved migration context
        // Enforces session existence, anti-IDOR ownership (404), terminal COMPLETED status (409),
        // and all 12 domain eligibility rules
        ApprovedMigrationContext context = executionService.prepareExecution(request, authenticatedUserId);

        // 2. Guard against empty approved selection (zero destination mutation permitted)
        if (!context.hasApprovedTracks()) {
            throw new InvalidStateException(String.format(
                    "Cannot create destination playlist: approved track selection is empty for review session %s",
                    context.reviewSessionId()
            ));
        }

        // 3. Resolve and validate destination playlist metadata
        String title = context.targetPlaylistTitle();
        if (title == null || title.isBlank()) {
            throw new IllegalArgumentException("Destination playlist title must not be blank");
        }
        String trimmedTitle = title.trim();
        if (trimmedTitle.length() > YouTubePlaylistService.MAX_TITLE_LENGTH) {
            throw new IllegalArgumentException(
                    "Playlist title exceeds maximum length of " + YouTubePlaylistService.MAX_TITLE_LENGTH + " characters"
            );
        }

        String trimmedDescription = null;
        if (context.targetPlaylistDescription().isPresent()) {
            String desc = context.targetPlaylistDescription().get();
            if (desc != null) {
                trimmedDescription = desc.trim();
                if (trimmedDescription.length() > YouTubePlaylistService.MAX_DESCRIPTION_LENGTH) {
                    throw new IllegalArgumentException(
                            "Playlist description exceeds maximum length of " + YouTubePlaylistService.MAX_DESCRIPTION_LENGTH + " characters"
                    );
                }
            }
        }

        // 4. Create destination playlist through provider abstraction
        CreateDestinationPlaylistRequest createRequest = new CreateDestinationPlaylistRequest(
                trimmedTitle,
                trimmedDescription,
                context.isPrivate()
        );

        DestinationPlaylist destinationPlaylist;
        try {
            destinationPlaylist = musicDestination.createPlaylist(authenticatedUserId, createRequest);
        } catch (ResourceAccessException ex) {
            String sanitizedMsg = sanitize(ex.getMessage());
            log.warn("Destination playlist creation request timed out or network failed for session {}. Remote outcome is UNCERTAIN: {}",
                    context.reviewSessionId(), sanitizedMsg);
            throw ex;
        } catch (ExternalProviderException ex) {
            String sanitizedMsg = sanitize(ex.getMessage());
            log.error("External provider rejected playlist creation for session {}: {}",
                    context.reviewSessionId(), sanitizedMsg);
            throw ex;
        } catch (RestClientException ex) {
            String sanitizedMsg = sanitize(ex.getMessage());
            log.error("Provider client error creating playlist for session {}: {}",
                    context.reviewSessionId(), sanitizedMsg);
            throw ex;
        } catch (Exception ex) {
            String sanitizedMsg = sanitize(ex.getMessage());
            log.error("Unexpected error creating destination playlist for session {}: {}",
                    context.reviewSessionId(), sanitizedMsg);
            throw ex;
        }

        // 5. Validate provider response identity
        if (destinationPlaylist == null || destinationPlaylist.id() == null || destinationPlaylist.id().isBlank()) {
            log.error("Destination provider returned null or blank playlist ID for session {}", context.reviewSessionId());
            throw new ExternalProviderException("Destination provider returned empty or missing playlist ID");
        }

        // 6. Assemble and return typed creation result (STRICTLY ZERO TRACK INSERTIONS)
        String playlistUrl = destinationPlaylist.url() != null && !destinationPlaylist.url().isBlank()
                ? destinationPlaylist.url()
                : "https://www.youtube.com/playlist?list=" + destinationPlaylist.id().trim();

        Platform platform = destinationPlaylist.platform() != null
                ? destinationPlaylist.platform()
                : (musicDestination.getPlatform() != null ? musicDestination.getPlatform() : Platform.YOUTUBE);

        String effectiveTitle = destinationPlaylist.title() != null && !destinationPlaylist.title().isBlank()
                ? destinationPlaylist.title()
                : trimmedTitle;

        String effectiveDescription = destinationPlaylist.description() != null
                ? destinationPlaylist.description()
                : trimmedDescription;

        log.info("Successfully created destination playlist '{}' ({}) with {} approved tracks for review session {}",
                effectiveTitle, destinationPlaylist.id().trim(), context.approvedTrackCount(), context.reviewSessionId());

        return new DestinationPlaylistCreationResult(
                context.reviewSessionId(),
                context.planId(),
                authenticatedUserId,
                platform,
                destinationPlaylist.id().trim(),
                playlistUrl,
                effectiveTitle,
                effectiveDescription,
                context.isPrivate(),
                context.approvedTrackCount(),
                Instant.now()
        );
    }

    @Override
    public DestinationPlaylistCreationResult createDestinationPlaylist(
            UUID reviewSessionId,
            UUID authenticatedUserId
    ) {
        if (reviewSessionId == null) {
            throw new IllegalArgumentException("Review session ID must not be null");
        }
        ApprovedMigrationExecutionRequest req = ApprovedMigrationExecutionRequest.of(reviewSessionId, authenticatedUserId);
        return createDestinationPlaylist(req, authenticatedUserId);
    }

    private String sanitize(String message) {
        if (message == null) {
            return "null";
        }
        return TOKEN_PATTERN.matcher(message).replaceAll("$1[REDACTED]");
    }
}
