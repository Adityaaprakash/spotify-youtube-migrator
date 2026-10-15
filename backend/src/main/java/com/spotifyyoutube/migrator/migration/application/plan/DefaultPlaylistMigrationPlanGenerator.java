package com.spotifyyoutube.migrator.migration.application.plan;

import com.spotifyyoutube.migrator.matching.discovery.CandidateDiscoveryRequest;
import com.spotifyyoutube.migrator.matching.discovery.CandidateDiscoveryResult;
import com.spotifyyoutube.migrator.matching.discovery.CandidateDiscoveryService;
import com.spotifyyoutube.migrator.matching.discovery.DiscoveryOptions;
import com.spotifyyoutube.migrator.matching.engine.MatchDecision;
import com.spotifyyoutube.migrator.matching.engine.TrackMatchRequest;
import com.spotifyyoutube.migrator.matching.engine.TrackMatchResult;
import com.spotifyyoutube.migrator.matching.engine.TrackMatchingEngine;
import com.spotifyyoutube.migrator.matching.normalization.NormalizationOrchestrator;
import com.spotifyyoutube.migrator.matching.normalization.NormalizedTrackMetadata;
import com.spotifyyoutube.migrator.matching.normalization.RawTrackMetadata;
import com.spotifyyoutube.migrator.migration.application.destination.model.DestinationTrackCandidate;
import com.spotifyyoutube.migrator.playlist.api.dto.PlaylistResponse;
import com.spotifyyoutube.migrator.playlist.api.dto.TrackResponse;
import com.spotifyyoutube.migrator.spotify.application.SpotifyPlaylistService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Default implementation of {@link PlaylistMigrationPlanGenerator}.
 * <p>
 * Evaluates candidate discovery and matching to generate an immutable, point-in-time
 * {@link MigrationPlan} without performing any destination mutations (no playlist creation,
 * no video insertion).
 */
@Service
public class DefaultPlaylistMigrationPlanGenerator implements PlaylistMigrationPlanGenerator {

    private static final Logger log = LoggerFactory.getLogger(DefaultPlaylistMigrationPlanGenerator.class);

    private final SpotifyPlaylistService spotifyPlaylistService;
    private final NormalizationOrchestrator normalizationOrchestrator;
    private final CandidateDiscoveryService candidateDiscoveryService;
    private final TrackMatchingEngine matchingEngine;

    public DefaultPlaylistMigrationPlanGenerator(
            SpotifyPlaylistService spotifyPlaylistService,
            NormalizationOrchestrator normalizationOrchestrator,
            CandidateDiscoveryService candidateDiscoveryService,
            TrackMatchingEngine matchingEngine
    ) {
        this.spotifyPlaylistService = Objects.requireNonNull(spotifyPlaylistService, "SpotifyPlaylistService must not be null");
        this.normalizationOrchestrator = Objects.requireNonNull(normalizationOrchestrator, "NormalizationOrchestrator must not be null");
        this.candidateDiscoveryService = Objects.requireNonNull(candidateDiscoveryService, "CandidateDiscoveryService must not be null");
        this.matchingEngine = Objects.requireNonNull(matchingEngine, "TrackMatchingEngine must not be null");
    }

    @Override
    public MigrationPlan generatePlan(UUID userId, String sourcePlaylistId, boolean deduplicate) {
        Objects.requireNonNull(userId, "userId must not be null");
        Objects.requireNonNull(sourcePlaylistId, "sourcePlaylistId must not be null");
        if (sourcePlaylistId.isBlank()) {
            throw new IllegalArgumentException("sourcePlaylistId must not be blank");
        }

        // 1. Fetch Source Playlist Metadata
        PlaylistResponse sourcePlaylist = spotifyPlaylistService.getPlaylistMetadata(userId, sourcePlaylistId);
        String playlistTitle = sourcePlaylist != null && sourcePlaylist.name() != null && !sourcePlaylist.name().isBlank()
                ? sourcePlaylist.name()
                : "Migrated Playlist";

        // 2. Fetch Source Playlist Tracks (Original Order Preserved)
        List<TrackResponse> sourceTracks = spotifyPlaylistService.getPlaylistTracks(userId, sourcePlaylistId);
        if (sourceTracks == null || sourceTracks.isEmpty()) {
            return MigrationPlan.empty(UUID.randomUUID(), sourcePlaylistId, playlistTitle, Instant.now());
        }

        // 3. Evaluate Each Track (Contiguous 0-indexed order)
        int totalTracks = sourceTracks.size();
        List<MigrationPlanTrack> planTracks = new ArrayList<>(totalTracks);
        Set<String> seenCandidateIds = new HashSet<>();

        for (int i = 0; i < totalTracks; i++) {
            TrackResponse track = sourceTracks.get(i);
            String sourceTrackId = "track-" + i;
            String trackTitle = "Unknown Title";
            String trackArtist = "Unknown Artist";
            if (track != null) {
                sourceTrackId = track.externalId() != null && !track.externalId().isBlank()
                        ? track.externalId()
                        : (track.id() != null ? track.id().toString() : "track-" + i);
                trackTitle = track.name() != null && !track.name().isBlank() ? track.name() : "Unknown Title";
                trackArtist = track.artist() != null && !track.artist().isBlank() ? track.artist() : "Unknown Artist";
            }

            try {
                if (track == null) {
                    throw new IllegalArgumentException("Source track entry at position " + i + " is null");
                }
                // Step A: Phase 5 Normalization
                Long durationMs = track.durationMs() != null ? track.durationMs().longValue() : null;
                RawTrackMetadata raw = RawTrackMetadata.of(trackTitle, trackArtist, track.album(), durationMs);
                NormalizedTrackMetadata normalized = normalizationOrchestrator.normalize(raw);

                // Step B: Phase 6 Candidate Discovery
                CandidateDiscoveryRequest discoveryReq = new CandidateDiscoveryRequest(normalized, DiscoveryOptions.defaults());
                CandidateDiscoveryResult discoveryResult = candidateDiscoveryService.discoverCandidates(discoveryReq);
                List<DestinationTrackCandidate> candidates = discoveryResult != null ? discoveryResult.candidates() : List.of();

                // Step C: Phase 7 Matching Engine
                TrackMatchRequest matchReq = new TrackMatchRequest(sourceTrackId, normalized, candidates);
                TrackMatchResult matchResult = matchingEngine.matchTrack(matchReq);

                // Step D: Decision Classification
                MatchDecision decision = matchResult != null ? matchResult.decision() : MatchDecision.NO_MATCH;
                Double overallScore = matchResult != null ? matchResult.score() : null;
                Double calibratedProb = matchResult != null ? matchResult.calibratedProbability() : null;
                List<String> matchReasons = matchResult != null ? matchResult.matchReasons() : List.of();
                DestinationTrackCandidate selectedCandidate = (matchResult != null && matchResult.hasSelectedCandidate())
                        ? matchResult.selectedCandidate()
                        : null;

                if (decision == MatchDecision.MATCH && selectedCandidate != null) {
                    String candidateId = selectedCandidate.id();
                    if (deduplicate && seenCandidateIds.contains(candidateId)) {
                        String dupReason = "Duplicate: destination candidate '" + candidateId + "' already selected for an earlier track";
                        planTracks.add(MigrationPlanTrack.duplicate(
                                i, sourceTrackId, trackTitle, trackArtist,
                                selectedCandidate, overallScore, calibratedProb, dupReason
                        ));
                    } else {
                        seenCandidateIds.add(candidateId);
                        planTracks.add(MigrationPlanTrack.autoMatch(
                                i, sourceTrackId, trackTitle, trackArtist,
                                selectedCandidate, overallScore, calibratedProb, matchReasons
                        ));
                    }
                } else if (decision == MatchDecision.REVIEW_REQUIRED) {
                    planTracks.add(MigrationPlanTrack.reviewRequired(
                            i, sourceTrackId, trackTitle, trackArtist,
                            selectedCandidate, overallScore, calibratedProb, matchReasons
                    ));
                } else {
                    // NO_MATCH or unselected candidate
                    planTracks.add(MigrationPlanTrack.unmatched(
                            i, sourceTrackId, trackTitle, trackArtist, matchReasons
                    ));
                }
            } catch (Exception ex) {
                String rawMsg = ex.getMessage() != null ? ex.getMessage() : ex.getClass().getSimpleName();
                String sanitizedMsg = MigrationPlanTrack.sanitize(rawMsg);
                log.warn("Planning failed for source track {} at position {}: {}", sourceTrackId, i, sanitizedMsg);
                planTracks.add(MigrationPlanTrack.failed(
                        i, sourceTrackId, trackTitle, trackArtist, "Track planning failure: " + sanitizedMsg
                ));
            }
        }

        // 4. Build Reconciled Plan and Summary
        MigrationPlanSummary summary = MigrationPlanSummary.fromTracks(planTracks);
        UUID planId = UUID.randomUUID();
        Instant createdAt = Instant.now();

        return new MigrationPlan(planId, sourcePlaylistId, playlistTitle, createdAt, planTracks, summary);
    }
}
