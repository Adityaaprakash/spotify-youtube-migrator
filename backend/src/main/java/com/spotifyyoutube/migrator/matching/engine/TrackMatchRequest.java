package com.spotifyyoutube.migrator.matching.engine;

import com.spotifyyoutube.migrator.matching.discovery.CandidateDiscoveryResult;
import com.spotifyyoutube.migrator.matching.normalization.NormalizedTrackMetadata;
import com.spotifyyoutube.migrator.migration.application.destination.model.DestinationTrackCandidate;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Immutable input request for the matching engine.
 * Encapsulates normalized source metadata from Phase 5 and destination candidates from Phase 6.
 * <p>
 * <b>Identity Handling:</b> Because {@link NormalizedTrackMetadata} represents pure musical metadata
 * and intentionally does not hold provider entity identifiers, the optional {@link #sourceTrackId()} field
 * carries the source identity (e.g. Spotify track ID or migration task source track ID) alongside the metadata.
 * <p>
 * <b>Empty Candidates Policy:</b> An empty candidate collection is explicitly valid. When candidate discovery
 * returns zero candidates (e.g. {@code CandidateDiscoveryStatus.EMPTY}), matching will cleanly evaluate to
 * {@link MatchDecision#NO_MATCH} without throwing an exception.
 *
 * @param sourceTrackId optional source track identifier (e.g. Spotify ID), trimmed or null
 * @param sourceTrack non-null normalized source metadata produced by Phase 5 normalization
 * @param candidates non-null, unmodifiable list of destination candidates produced by Phase 6 discovery
 */
public record TrackMatchRequest(
        String sourceTrackId,
        NormalizedTrackMetadata sourceTrack,
        List<DestinationTrackCandidate> candidates
) {
    public TrackMatchRequest {
        Objects.requireNonNull(sourceTrack, "NormalizedTrackMetadata must not be null");
        Objects.requireNonNull(candidates, "Candidates list must not be null");
        sourceTrackId = sourceTrackId != null ? sourceTrackId.trim() : null;
        candidates = List.copyOf(candidates);
    }

    /**
     * Convenience constructor without an explicit source track identifier.
     */
    public TrackMatchRequest(NormalizedTrackMetadata sourceTrack, List<DestinationTrackCandidate> candidates) {
        this(null, sourceTrack, candidates);
    }

    /**
     * Factory method creating a match request directly from a Phase 6 {@link CandidateDiscoveryResult}.
     */
    public static TrackMatchRequest fromDiscoveryResult(CandidateDiscoveryResult discoveryResult) {
        return fromDiscoveryResult(null, discoveryResult);
    }

    /**
     * Factory method creating a match request from a Phase 6 {@link CandidateDiscoveryResult} with a source identifier.
     */
    public static TrackMatchRequest fromDiscoveryResult(String sourceTrackId, CandidateDiscoveryResult discoveryResult) {
        Objects.requireNonNull(discoveryResult, "CandidateDiscoveryResult must not be null");
        return new TrackMatchRequest(
                sourceTrackId,
                discoveryResult.sourceTrack(),
                discoveryResult.candidates()
        );
    }

    public boolean hasCandidates() {
        return !candidates.isEmpty();
    }

    public int candidateCount() {
        return candidates.size();
    }

    public boolean hasSourceTrackId() {
        return sourceTrackId != null && !sourceTrackId.isBlank();
    }

    public Optional<String> getSourceTrackId() {
        return Optional.ofNullable(sourceTrackId);
    }
}
