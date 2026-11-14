package com.spotifyyoutube.migrator.migration.reliability.reconciliation;

import com.spotifyyoutube.migrator.common.exception.ExternalProviderException;
import com.spotifyyoutube.migrator.migration.application.destination.MusicDestination;
import com.spotifyyoutube.migrator.migration.application.destination.model.DestinationPlaylist;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Service coordinating destination playlist creation reconciliation (Phase 13F).
 * <p>
 * Reconciles ambiguous external playlist creation outcomes (network timeout, connection reset, 500/502/503)
 * by searching the authenticated user's remote playlists for the deterministic correlation marker.
 * <p>
 * Enforces the bounded verification policy:
 * <ol>
 *   <li>Initial read of user's destination playlists across all pagination pages.</li>
 *   <li>Exact marker matching against persisted operation UUID.</li>
 *   <li>Title collision detection (same title but missing/different marker).</li>
 *   <li>Multiple matching marker detection (integrity violation).</li>
 *   <li>Mandatory 3-second bounded grace period followed by second confirmation read before concluding absence.</li>
 *   <li>Zero mutations executed inside database transactions.</li>
 * </ol>
 */
@Service
public class DestinationPlaylistReconciliationService {

    private static final Logger log = LoggerFactory.getLogger(DestinationPlaylistReconciliationService.class);

    public static final Duration DEFAULT_VERIFICATION_GRACE_PERIOD = Duration.ofSeconds(3);

    private final MusicDestination musicDestination;
    private Duration verificationGracePeriod = DEFAULT_VERIFICATION_GRACE_PERIOD;

    public DestinationPlaylistReconciliationService(MusicDestination musicDestination) {
        this.musicDestination = Objects.requireNonNull(musicDestination, "MusicDestination must not be null");
    }

    /**
     * Visible for testing: override verification grace period duration.
     */
    public void setVerificationGracePeriod(Duration verificationGracePeriod) {
        this.verificationGracePeriod = verificationGracePeriod != null
                ? verificationGracePeriod
                : DEFAULT_VERIFICATION_GRACE_PERIOD;
    }

    /**
     * Reconciles destination playlist creation after an ambiguous outcome or recovery event.
     *
     * @param userId        authenticated user ID owning destination credentials
     * @param operationId   stable persisted playlist creation operation UUID
     * @param expectedTitle target playlist title for collision detection
     * @return typed PlaylistReconciliationResult with verified outcome
     */
    public PlaylistReconciliationResult reconcilePlaylistCreation(
            UUID userId,
            UUID operationId,
            String expectedTitle
    ) {
        if (userId == null) {
            throw new IllegalArgumentException("User ID must not be null");
        }
        if (operationId == null) {
            throw new IllegalArgumentException("Operation ID must not be null");
        }

        log.info("Starting destination playlist reconciliation for user {} and operation {}", userId, operationId);

        // Stage 1: Initial read of remote playlists
        List<DestinationPlaylist> initialPlaylists;
        try {
            initialPlaylists = musicDestination.getUserPlaylists(userId);
        } catch (ExternalProviderException ex) {
            log.error("Provider error during initial playlist reconciliation for user {}: status={}, msg={}",
                    userId, ex.getProviderStatusCode(), ex.getMessage());
            return PlaylistReconciliationResult.providerError("Provider error HTTP " + ex.getProviderStatusCode() + ": " + ex.getMessage());
        } catch (Exception ex) {
            log.error("Communication failure during initial playlist reconciliation for user {}: {}",
                    userId, ex.getMessage());
            return PlaylistReconciliationResult.providerError("Communication failure: " + ex.getMessage());
        }

        // Evaluate stage 1 results
        PlaylistReconciliationResult stage1Result = evaluatePlaylists(initialPlaylists, operationId, expectedTitle);
        if (stage1Result.outcome() == PlaylistReconciliationOutcome.EXACT_MATCH_FOUND
                || stage1Result.outcome() == PlaylistReconciliationOutcome.MULTIPLE_MATCHING_MARKERS
                || stage1Result.outcome() == PlaylistReconciliationOutcome.TITLE_COLLISION) {
            log.info("Stage 1 reconciliation resolved: outcome={} for operation {}",
                    stage1Result.outcome(), operationId);
            return stage1Result;
        }

        // Stage 2: Bounded grace period to account for provider replication lag
        log.info("Operation {} not found on initial read; waiting {}ms grace period before second confirmation read",
                operationId, verificationGracePeriod.toMillis());
        try {
            if (!verificationGracePeriod.isZero() && !verificationGracePeriod.isNegative()) {
                Thread.sleep(verificationGracePeriod.toMillis());
            }
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            log.warn("Interrupted during reconciliation grace period for operation {}", operationId);
            return PlaylistReconciliationResult.providerError("Interrupted during reconciliation grace period");
        }

        // Stage 3: Second confirmation read
        List<DestinationPlaylist> secondPlaylists;
        try {
            secondPlaylists = musicDestination.getUserPlaylists(userId);
        } catch (ExternalProviderException ex) {
            log.error("Provider error during second playlist reconciliation for user {}: status={}, msg={}",
                    userId, ex.getProviderStatusCode(), ex.getMessage());
            return PlaylistReconciliationResult.providerError("Provider error HTTP " + ex.getProviderStatusCode() + ": " + ex.getMessage());
        } catch (Exception ex) {
            log.error("Communication failure during second playlist reconciliation for user {}: {}",
                    userId, ex.getMessage());
            return PlaylistReconciliationResult.providerError("Communication failure on second read: " + ex.getMessage());
        }

        // Evaluate stage 3 results
        PlaylistReconciliationResult stage2Result = evaluatePlaylists(secondPlaylists, operationId, expectedTitle);
        if (stage2Result.outcome() == PlaylistReconciliationOutcome.EXACT_MATCH_FOUND
                || stage2Result.outcome() == PlaylistReconciliationOutcome.MULTIPLE_MATCHING_MARKERS
                || stage2Result.outcome() == PlaylistReconciliationOutcome.TITLE_COLLISION) {
            log.info("Stage 2 reconciliation resolved: outcome={} for operation {}",
                    stage2Result.outcome(), operationId);
            return stage2Result;
        }

        // Conclusively absent after two reads separated by the bounded grace period
        log.info("Operation {} conclusively absent after bounded two-stage verification", operationId);
        return PlaylistReconciliationResult.conclusivelyAbsent(
                "No playlist bearing operation marker " + operationId + " or conflicting title found after bounded verification."
        );
    }

    private PlaylistReconciliationResult evaluatePlaylists(
            List<DestinationPlaylist> playlists,
            UUID operationId,
            String expectedTitle
    ) {
        if (playlists == null || playlists.isEmpty()) {
            return PlaylistReconciliationResult.conclusivelyAbsent("Zero remote playlists observed");
        }

        List<DestinationPlaylist> matchingMarkerPlaylists = new ArrayList<>();
        DestinationPlaylist titleCollisionPlaylist = null;

        String normalizedExpectedTitle = expectedTitle != null ? expectedTitle.trim() : null;

        for (DestinationPlaylist playlist : playlists) {
            if (playlist == null || playlist.id() == null) {
                continue;
            }

            String description = playlist.description();

            // Check for exact matching marker
            if (PlaylistCorrelationMarker.matchesOperation(description, operationId)) {
                matchingMarkerPlaylists.add(playlist);
            }

            // Check for title collision if expected title was provided
            if (normalizedExpectedTitle != null && playlist.title() != null) {
                if (playlist.title().trim().equalsIgnoreCase(normalizedExpectedTitle)) {
                    // Title matches, check if it DOES NOT match the operation marker
                    if (!PlaylistCorrelationMarker.matchesOperation(description, operationId)) {
                        titleCollisionPlaylist = playlist;
                    }
                }
            }
        }

        // Case D: Multiple matching markers (integrity violation)
        if (matchingMarkerPlaylists.size() > 1) {
            log.error("Integrity violation: {} playlists found with identical operation marker {}",
                    matchingMarkerPlaylists.size(), operationId);
            return PlaylistReconciliationResult.multipleMarkersFound(
                    matchingMarkerPlaylists,
                    "Integrity violation: multiple playlists found with marker for operation " + operationId
            );
        }

        // Case A: Exact unique marker found
        if (matchingMarkerPlaylists.size() == 1) {
            DestinationPlaylist matched = matchingMarkerPlaylists.get(0);
            log.info("Discovered remote playlist {} for operation {}", matched.id(), operationId);
            return PlaylistReconciliationResult.exactMatch(matched);
        }

        // Case C: Title collision without matching marker
        if (titleCollisionPlaylist != null) {
            log.warn("Title collision detected for title '{}' on playlist {} without matching marker {}",
                    normalizedExpectedTitle, titleCollisionPlaylist.id(), operationId);
            return PlaylistReconciliationResult.titleCollision(
                    titleCollisionPlaylist,
                    "Existing remote playlist '" + titleCollisionPlaylist.title() + "' (" + titleCollisionPlaylist.id()
                            + ") collides with requested title but lacks operation marker " + operationId
            );
        }

        // Absent on this inspection
        return PlaylistReconciliationResult.conclusivelyAbsent("Marker not observed on this pass");
    }
}
