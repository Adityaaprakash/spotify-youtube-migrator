package com.spotifyyoutube.migrator.migration.reliability.reconciliation;

import com.spotifyyoutube.migrator.migration.application.destination.MusicDestination;
import com.spotifyyoutube.migrator.migration.application.destination.model.DestinationPlaylistInspection;
import com.spotifyyoutube.migrator.migration.execution.domain.ExecutionTrackRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.function.BooleanSupplier;

/**
 * Service coordinating ambiguous track insertion reconciliation (Phase 13F).
 * <p>
 * Implements the mandatory multi-stage reconciliation sequence after an ambiguous insertion outcome:
 * <ol>
 *   <li>Mandatory initial 3-second grace period (accounting for YouTube read-after-write replication lag).</li>
 *   <li>First destination playlist inspection with complete pagination.</li>
 *   <li>Deterministic 1-to-1 occurrence matching preserving intentional duplicates.</li>
 *   <li>If verified on first read: returns VERIFIED_ADDED with the confirmed playlistItemId.</li>
 *   <li>If not verified: waits a second 3-second grace period.</li>
 *   <li>Second confirmation read and occurrence matching.</li>
 *   <li>If verified on second read: returns VERIFIED_ADDED.</li>
 *   <li>If still absent after second read: returns CONFIRMED_ABSENT, making the track safely eligible for retry evaluation.</li>
 *   <li>If ordering anomalies, extra remote occurrences, or duplicate item IDs are detected: returns INTEGRITY_VIOLATION.</li>
 *   <li>All external HTTP requests and grace periods are executed OUTSIDE database transactions.</li>
 * </ol>
 */
@Service
public class TrackInsertionReconciliationService {

    private static final Logger log = LoggerFactory.getLogger(TrackInsertionReconciliationService.class);

    public static final Duration DEFAULT_GRACE_PERIOD = Duration.ofSeconds(3);

    private final MusicDestination musicDestination;
    private Duration gracePeriod = DEFAULT_GRACE_PERIOD;

    public TrackInsertionReconciliationService(MusicDestination musicDestination) {
        this.musicDestination = Objects.requireNonNull(musicDestination, "MusicDestination must not be null");
    }

    /**
     * Visible for testing: override grace period duration.
     */
    public void setGracePeriod(Duration gracePeriod) {
        this.gracePeriod = gracePeriod != null ? gracePeriod : DEFAULT_GRACE_PERIOD;
    }

    /**
     * Reconciles an ambiguous track insertion outcome using bounded two-stage inspection.
     *
     * @param userId          authenticated user ID
     * @param playlistId      destination playlist ID
     * @param trackId         the ambiguous track record ID
     * @param allSourceTracks snapshot of all execution tracks ordered by targetPosition ascending
     * @param isCancelled     optional callback to detect ownership loss during grace periods
     * @return typed TrackInsertionReconciliationResult
     */
    public TrackInsertionReconciliationResult reconcileTrackInsertion(
            UUID userId,
            String playlistId,
            UUID trackId,
            List<ExecutionTrackRecord> allSourceTracks,
            BooleanSupplier isCancelled
    ) {
        if (userId == null) {
            throw new IllegalArgumentException("User ID must not be null");
        }
        if (playlistId == null || playlistId.isBlank()) {
            throw new IllegalArgumentException("Playlist ID must not be blank");
        }
        if (trackId == null) {
            throw new IllegalArgumentException("Track ID must not be null");
        }
        if (allSourceTracks == null || allSourceTracks.isEmpty()) {
            throw new IllegalArgumentException("Source tracks list must not be empty");
        }

        log.info("Starting track insertion reconciliation for track {} in playlist {}", trackId, playlistId);

        // Stage 1: Mandatory initial grace period (minimum 3 seconds)
        if (sleepGracePeriod(isCancelled)) {
            return TrackInsertionReconciliationResult.inspectionFailed("Ownership lost or cancelled during initial grace period");
        }

        // Stage 2: First inspection read
        DestinationPlaylistInspection inspection1 = musicDestination.inspectPlaylist(userId, playlistId);
        if (inspection1 == null || !inspection1.isComplete()) {
            String error = inspection1 != null && inspection1.errorMessage() != null
                    ? inspection1.errorMessage()
                    : "Unknown provider inspection failure";
            log.warn("First inspection failed for playlist {}: {}", playlistId, error);
            return TrackInsertionReconciliationResult.inspectionFailed("First inspection failed: " + error);
        }

        // Occurrence match on first read
        TrackOccurrenceMatchResult match1 = TrackOccurrenceMatcher.matchTrack(allSourceTracks, inspection1.items(), trackId);
        if (match1.isIntegrityViolation()) {
            log.error("Integrity violation detected on first read for track {}: {}", trackId, match1.message());
            return TrackInsertionReconciliationResult.integrityViolation(match1.message());
        }
        if (match1.isVerifiedAdded()) {
            log.info("Track {} verified added on first read with playlistItemId {}",
                    trackId, match1.verifiedPlaylistItemId());
            return TrackInsertionReconciliationResult.verifiedAdded(match1.verifiedPlaylistItemId(), match1.message());
        }

        // Stage 3: Second grace period (minimum 3 seconds)
        log.info("Track {} not verified on first read; waiting second grace period of {}ms",
                trackId, gracePeriod.toMillis());
        if (sleepGracePeriod(isCancelled)) {
            return TrackInsertionReconciliationResult.inspectionFailed("Ownership lost or cancelled during second grace period");
        }

        // Stage 4: Second confirmation read
        DestinationPlaylistInspection inspection2 = musicDestination.inspectPlaylist(userId, playlistId);
        if (inspection2 == null || !inspection2.isComplete()) {
            String error = inspection2 != null && inspection2.errorMessage() != null
                    ? inspection2.errorMessage()
                    : "Unknown provider inspection failure";
            log.warn("Second inspection failed for playlist {}: {}", playlistId, error);
            return TrackInsertionReconciliationResult.inspectionFailed("Second inspection failed: " + error);
        }

        // Occurrence match on second read
        TrackOccurrenceMatchResult match2 = TrackOccurrenceMatcher.matchTrack(allSourceTracks, inspection2.items(), trackId);
        if (match2.isIntegrityViolation()) {
            log.error("Integrity violation detected on second read for track {}: {}", trackId, match2.message());
            return TrackInsertionReconciliationResult.integrityViolation(match2.message());
        }
        if (match2.isVerifiedAdded()) {
            log.info("Track {} verified added on second confirmation read with playlistItemId {}",
                    trackId, match2.verifiedPlaylistItemId());
            return TrackInsertionReconciliationResult.verifiedAdded(match2.verifiedPlaylistItemId(), match2.message());
        }

        // Conclusively confirmed absent after both grace periods and reads
        log.info("Track {} confirmed absent after bounded two-stage inspection", trackId);
        return TrackInsertionReconciliationResult.confirmedAbsent(
                "Track occurrence not found in destination playlist after bounded two-stage inspection"
        );
    }

    private boolean sleepGracePeriod(BooleanSupplier isCancelled) {
        if (isCancelled != null && isCancelled.getAsBoolean()) {
            return true;
        }
        if (!gracePeriod.isZero() && !gracePeriod.isNegative()) {
            try {
                Thread.sleep(gracePeriod.toMillis());
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                return true;
            }
        }
        return isCancelled != null && isCancelled.getAsBoolean();
    }
}
