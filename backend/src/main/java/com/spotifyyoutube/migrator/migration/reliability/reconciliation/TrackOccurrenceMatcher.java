package com.spotifyyoutube.migrator.migration.reliability.reconciliation;

import com.spotifyyoutube.migrator.migration.application.destination.model.DestinationPlaylistItemDetails;
import com.spotifyyoutube.migrator.migration.execution.domain.ExecutionTrackRecord;
import com.spotifyyoutube.migrator.migration.execution.domain.ExecutionTrackStatus;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Pure, deterministic matching engine between ordered source playlist occurrences and remote playlist items (Phase 13F).
 * <p>
 * Crucial invariants:
 * <ul>
 *   <li>Intentional duplicates are preserved: multiple source tracks sharing the same destination video ID
 *       are treated as distinct occurrences and mapped 1-to-1 to remote items.</li>
 *   <li>Never deduplicates by video ID alone.</li>
 *   <li>Preserves source targetPosition ordering.</li>
 *   <li>Enforces remote playlist item identity (using playlistItemId, not just video ID).</li>
 *   <li>Rejects duplicate remote playlist item ID assignments.</li>
 *   <li>Detects unexpected extra remote occurrences or ordering anomalies and fails closed into an integrity violation.</li>
 * </ul>
 */
public final class TrackOccurrenceMatcher {

    private TrackOccurrenceMatcher() {
        // Pure utility
    }

    /**
     * Matches the ambiguous source track against remote playlist items using deterministic 1-to-1 occurrence mapping.
     *
     * @param allSourceTracks  all tracks for this execution ordered by targetPosition ascending
     * @param remoteItems      all remote playlist items from destination inspection
     * @param targetTrackId    the specific track ID whose insertion outcome was ambiguous
     * @return typed match result
     */
    public static TrackOccurrenceMatchResult matchTrack(
            List<ExecutionTrackRecord> allSourceTracks,
            List<DestinationPlaylistItemDetails> remoteItems,
            UUID targetTrackId
    ) {
        Objects.requireNonNull(allSourceTracks, "allSourceTracks must not be null");
        Objects.requireNonNull(targetTrackId, "targetTrackId must not be null");

        List<DestinationPlaylistItemDetails> safeRemoteItems = remoteItems != null ? remoteItems : Collections.emptyList();

        // 1. Locate the target track
        ExecutionTrackRecord targetTrack = null;
        for (ExecutionTrackRecord track : allSourceTracks) {
            if (track != null && Objects.equals(track.getId(), targetTrackId)) {
                targetTrack = track;
                break;
            }
        }

        if (targetTrack == null) {
            return TrackOccurrenceMatchResult.integrityViolation("Target track " + targetTrackId + " not found in execution track list");
        }

        String targetVideoId = targetTrack.getDestinationTrackId();
        if (targetVideoId == null || targetVideoId.isBlank()) {
            return TrackOccurrenceMatchResult.integrityViolation("Target track " + targetTrackId + " has blank destination video ID");
        }

        // 2. Detect duplicate remote playlist item IDs (remote provider data corruption guard)
        Set<String> seenRemoteItemIds = new HashSet<>();
        for (DestinationPlaylistItemDetails item : safeRemoteItems) {
            if (item != null && item.playlistItemId() != null && !item.playlistItemId().isBlank()) {
                if (!seenRemoteItemIds.add(item.playlistItemId().trim())) {
                    return TrackOccurrenceMatchResult.integrityViolation(
                            "Duplicate remote playlist item ID discovered in inspection: " + item.playlistItemId()
                    );
                }
            }
        }

        // 3. Group source occurrences by video ID in targetPosition order
        Map<String, List<ExecutionTrackRecord>> sourceByVideo = new HashMap<>();
        for (ExecutionTrackRecord track : allSourceTracks) {
            if (track != null && track.getDestinationTrackId() != null) {
                sourceByVideo.computeIfAbsent(track.getDestinationTrackId(), k -> new ArrayList<>()).add(track);
            }
        }

        // 4. Group remote items by video ID in remote playlist order
        Map<String, List<DestinationPlaylistItemDetails>> remoteByVideo = new HashMap<>();
        for (DestinationPlaylistItemDetails item : safeRemoteItems) {
            if (item != null && item.destinationTrackId() != null && !item.destinationTrackId().isBlank()) {
                remoteByVideo.computeIfAbsent(item.destinationTrackId().trim(), k -> new ArrayList<>()).add(item);
            }
        }

        // 5. Determine the 1-based occurrence index of targetTrack among identical source tracks
        List<ExecutionTrackRecord> occurrences = sourceByVideo.getOrDefault(targetVideoId, Collections.emptyList());
        int targetOccurrenceIndex = -1;
        int dispatchedOccurrenceCount = 0;

        for (int i = 0; i < occurrences.size(); i++) {
            ExecutionTrackRecord occ = occurrences.get(i);
            if (occ.getStatus() == ExecutionTrackStatus.ADDED
                    || occ.getStatus() == ExecutionTrackStatus.ADDING
                    || occ.getStatus() == ExecutionTrackStatus.UNCERTAIN
                    || Objects.equals(occ.getId(), targetTrackId)) {
                dispatchedOccurrenceCount++;
            }
            if (Objects.equals(occ.getId(), targetTrackId)) {
                targetOccurrenceIndex = i + 1; // 1-based index (e.g. 1st, 2nd, 3rd occurrence of this video)
            }
        }

        if (targetOccurrenceIndex == -1) {
            return TrackOccurrenceMatchResult.integrityViolation(
                    "Target track " + targetTrackId + " missing from video occurrence grouping"
            );
        }

        // 6. Inspect remote occurrences of this video
        List<DestinationPlaylistItemDetails> remoteOccurrences = remoteByVideo.getOrDefault(targetVideoId, Collections.emptyList());
        int remoteCount = remoteOccurrences.size();

        // 7. Check for extra unexpected remote occurrences
        if (remoteCount > dispatchedOccurrenceCount) {
            return TrackOccurrenceMatchResult.integrityViolation(String.format(
                    "Observed %d remote occurrences of video %s, but only %d were dispatched (target occurrence %d)",
                    remoteCount, targetVideoId, dispatchedOccurrenceCount, targetOccurrenceIndex
            ));
        }

        // 8. Verify existing ADDED occurrences match prior remote items at their expected positions
        Set<String> alreadyAssignedItemIds = new HashSet<>();
        for (int i = 0; i < targetOccurrenceIndex - 1; i++) {
            ExecutionTrackRecord priorTrack = occurrences.get(i);
            if (priorTrack.getStatus() == ExecutionTrackStatus.ADDED) {
                String assignedItemId = priorTrack.getDestinationPlaylistItemId();
                if (assignedItemId == null || assignedItemId.isBlank()) {
                    return TrackOccurrenceMatchResult.integrityViolation(
                            "Prior ADDED track " + priorTrack.getId() + " lacks destinationPlaylistItemId"
                    );
                }
                String trimmedAssignedId = assignedItemId.trim();

                // 1. Verify remote has an item at this occurrence position
                if (remoteOccurrences.size() <= i) {
                    return TrackOccurrenceMatchResult.integrityViolation(String.format(
                            "Prior ADDED occurrence %d of video %s (item %s) is missing remotely (only %d remote items found)",
                            i + 1, targetVideoId, trimmedAssignedId, remoteOccurrences.size()
                    ));
                }

                // 2. Verify the remote item at this exact occurrence index matches the persisted destinationPlaylistItemId
                DestinationPlaylistItemDetails priorRemoteItem = remoteOccurrences.get(i);
                String actualRemoteItemId = priorRemoteItem.playlistItemId() != null
                        ? priorRemoteItem.playlistItemId().trim()
                        : null;

                if (!Objects.equals(trimmedAssignedId, actualRemoteItemId)) {
                    return TrackOccurrenceMatchResult.integrityViolation(String.format(
                            "Prior ADDED occurrence %d of video %s expected remote item %s but found %s at position %d",
                            i + 1, targetVideoId, trimmedAssignedId, actualRemoteItemId, i + 1
                    ));
                }

                alreadyAssignedItemIds.add(trimmedAssignedId);
            }
        }

        // 9. Evaluate candidate remote item for targetTrack
        if (remoteCount >= targetOccurrenceIndex) {
            DestinationPlaylistItemDetails matchedItem = remoteOccurrences.get(targetOccurrenceIndex - 1);
            String matchedItemId = matchedItem.playlistItemId() != null ? matchedItem.playlistItemId().trim() : null;

            if (matchedItemId == null || matchedItemId.isBlank()) {
                return TrackOccurrenceMatchResult.integrityViolation("Remote item has blank playlistItemId");
            }

            if (alreadyAssignedItemIds.contains(matchedItemId)) {
                return TrackOccurrenceMatchResult.integrityViolation(
                        "Remote playlist item ID " + matchedItemId + " already assigned to a prior track"
                );
            }

            // Successfully verified as inserted!
            return TrackOccurrenceMatchResult.verifiedAdded(
                    matchedItemId,
                    String.format(
                            "Occurrence %d of video %s verified remotely as playlist item %s",
                            targetOccurrenceIndex, targetVideoId, matchedItemId
                    )
            );
        } else {
            // Remote playlist has fewer occurrences than the target occurrence index -> definitely not inserted
            return TrackOccurrenceMatchResult.confirmedAbsent(String.format(
                    "Occurrence %d of video %s not found remotely (only %d occurrences exist)",
                    targetOccurrenceIndex, targetVideoId, remoteCount
            ));
        }
    }
}
