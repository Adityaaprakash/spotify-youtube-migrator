package com.spotifyyoutube.migrator.matching.discovery;

import com.spotifyyoutube.migrator.matching.normalization.NormalizedTrackMetadata;
import com.spotifyyoutube.migrator.migration.application.destination.model.DestinationTrackCandidate;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Comprehensive, immutable structured result of candidate discovery.
 * Encapsulates the source track, deduplicated candidates, detailed query execution records,
 * and explicit outcome status.
 *
 * @param sourceTrack the normalized track metadata for which discovery was conducted
 * @param candidates deduplicated, bounded list of destination track candidates in encounter order
 * @param discoveredCandidates list of candidates paired with their discovering queries
 * @param executedQueries history of executed search queries and their outcomes
 * @param status the overall outcome status
 * @param errorMessage diagnostic error message, or null if fully successful
 */
public record CandidateDiscoveryResult(
        NormalizedTrackMetadata sourceTrack,
        List<DestinationTrackCandidate> candidates,
        List<DiscoveredCandidate> discoveredCandidates,
        List<SearchQueryExecution> executedQueries,
        CandidateDiscoveryStatus status,
        String errorMessage
) {
    public CandidateDiscoveryResult {
        Objects.requireNonNull(sourceTrack, "NormalizedTrackMetadata must not be null");
        Objects.requireNonNull(status, "CandidateDiscoveryStatus must not be null");
        candidates = candidates != null ? List.copyOf(candidates) : List.of();
        discoveredCandidates = discoveredCandidates != null ? List.copyOf(discoveredCandidates) : List.of();
        executedQueries = executedQueries != null ? List.copyOf(executedQueries) : List.of();
    }

    public static CandidateDiscoveryResult success(
            NormalizedTrackMetadata sourceTrack,
            List<DiscoveredCandidate> discoveredCandidates,
            List<SearchQueryExecution> executedQueries
    ) {
        List<DestinationTrackCandidate> deduplicated = deduplicateCandidates(discoveredCandidates);
        return new CandidateDiscoveryResult(
                sourceTrack,
                deduplicated,
                discoveredCandidates,
                executedQueries,
                CandidateDiscoveryStatus.SUCCESS,
                null
        );
    }

    public static CandidateDiscoveryResult empty(
            NormalizedTrackMetadata sourceTrack,
            List<SearchQueryExecution> executedQueries
    ) {
        return new CandidateDiscoveryResult(
                sourceTrack,
                List.of(),
                List.of(),
                executedQueries,
                CandidateDiscoveryStatus.EMPTY,
                null
        );
    }

    public static CandidateDiscoveryResult partialFailure(
            NormalizedTrackMetadata sourceTrack,
            List<DiscoveredCandidate> discoveredCandidates,
            List<SearchQueryExecution> executedQueries,
            String errorMessage
    ) {
        List<DestinationTrackCandidate> deduplicated = deduplicateCandidates(discoveredCandidates);
        return new CandidateDiscoveryResult(
                sourceTrack,
                deduplicated,
                discoveredCandidates,
                executedQueries,
                CandidateDiscoveryStatus.PARTIAL_FAILURE,
                errorMessage
        );
    }

    public static CandidateDiscoveryResult failure(
            NormalizedTrackMetadata sourceTrack,
            List<SearchQueryExecution> executedQueries,
            String errorMessage
    ) {
        return new CandidateDiscoveryResult(
                sourceTrack,
                List.of(),
                List.of(),
                executedQueries,
                CandidateDiscoveryStatus.FAILED,
                errorMessage
        );
    }

    private static List<DestinationTrackCandidate> deduplicateCandidates(List<DiscoveredCandidate> discovered) {
        if (discovered == null || discovered.isEmpty()) {
            return List.of();
        }
        Map<String, DestinationTrackCandidate> unique = new LinkedHashMap<>();
        for (DiscoveredCandidate dc : discovered) {
            if (dc != null && dc.candidate() != null && dc.candidate().id() != null && !dc.candidate().id().isBlank()) {
                unique.putIfAbsent(dc.candidate().id().trim(), dc.candidate());
            }
        }
        return List.copyOf(unique.values());
    }

    public boolean hasCandidates() {
        return !candidates.isEmpty();
    }

    public boolean isSuccessful() {
        return status == CandidateDiscoveryStatus.SUCCESS || status == CandidateDiscoveryStatus.PARTIAL_FAILURE;
    }

    public boolean isEmpty() {
        return status == CandidateDiscoveryStatus.EMPTY;
    }

    public boolean isFailed() {
        return status == CandidateDiscoveryStatus.FAILED;
    }

    public int candidateCount() {
        return candidates.size();
    }
}
