package com.spotifyyoutube.migrator.migration.application.orchestration;

import com.spotifyyoutube.migrator.common.exception.InvalidStateException;
import com.spotifyyoutube.migrator.common.exception.ResourceNotFoundException;
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
import com.spotifyyoutube.migrator.migration.application.destination.MusicDestination;
import com.spotifyyoutube.migrator.migration.application.destination.model.AddTrackToDestinationPlaylistRequest;
import com.spotifyyoutube.migrator.migration.application.destination.model.CreateDestinationPlaylistRequest;
import com.spotifyyoutube.migrator.migration.application.destination.model.DestinationPlaylist;
import com.spotifyyoutube.migrator.migration.domain.MigrationJob;
import com.spotifyyoutube.migrator.migration.domain.MigrationStatus;
import com.spotifyyoutube.migrator.migration.domain.MigrationTask;
import com.spotifyyoutube.migrator.migration.repository.MigrationJobRepository;
import com.spotifyyoutube.migrator.migration.repository.MigrationTaskRepository;
import com.spotifyyoutube.migrator.playlist.api.dto.PlaylistResponse;
import com.spotifyyoutube.migrator.playlist.api.dto.TrackResponse;
import com.spotifyyoutube.migrator.playlist.domain.Platform;
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
import java.util.regex.Pattern;

/**
 * Orchestrates the end-to-end playlist migration workflow connecting Spotify source tracks,
 * metadata normalization (Phase 5), candidate discovery (Phase 6), probability-calibrated
 * track matching (Phase 7), and destination playlist creation/insertion (Phase 4).
 * <p>
 * Enforces architectural invariants:
 * <ol>
 *   <li>Source playlist ordering is preserved strictly across all migration outcomes.</li>
 *   <li>Partial progress is preserved: individual track failures do not abort the migration.</li>
 *   <li>Security & Sanitization: access tokens, authorization headers, and secrets are sanitized from task records.</li>
 *   <li>Deduplication policy: duplicate destination additions can be prevented when identical targets are matched.</li>
 *   <li>Reviews held out: ambiguous matches requiring review are never automatically inserted into destination.</li>
 * </ol>
 */
@Service
public class PlaylistMigrationOrchestrator {

    private static final Logger log = LoggerFactory.getLogger(PlaylistMigrationOrchestrator.class);
    private static final Pattern TOKEN_PATTERN = Pattern.compile(
            "(?i)(bearer\\s+|(?:access_token|refresh_token|token|api_key|apikey|key|client_secret|secret|password)=)[^\\s&\"',;]+"
    );

    private final SpotifyPlaylistService spotifyPlaylistService;
    private final NormalizationOrchestrator normalizationOrchestrator;
    private final CandidateDiscoveryService candidateDiscoveryService;
    private final TrackMatchingEngine matchingEngine;
    private final MusicDestination musicDestination;
    private final MigrationJobRepository migrationJobRepository;
    private final MigrationTaskRepository migrationTaskRepository;

    public PlaylistMigrationOrchestrator(
            SpotifyPlaylistService spotifyPlaylistService,
            NormalizationOrchestrator normalizationOrchestrator,
            CandidateDiscoveryService candidateDiscoveryService,
            TrackMatchingEngine matchingEngine,
            MusicDestination musicDestination,
            MigrationJobRepository migrationJobRepository,
            MigrationTaskRepository migrationTaskRepository
    ) {
        this.spotifyPlaylistService = Objects.requireNonNull(spotifyPlaylistService, "SpotifyPlaylistService must not be null");
        this.normalizationOrchestrator = Objects.requireNonNull(normalizationOrchestrator, "NormalizationOrchestrator must not be null");
        this.candidateDiscoveryService = Objects.requireNonNull(candidateDiscoveryService, "CandidateDiscoveryService must not be null");
        this.matchingEngine = Objects.requireNonNull(matchingEngine, "TrackMatchingEngine must not be null");
        this.musicDestination = Objects.requireNonNull(musicDestination, "MusicDestination must not be null");
        this.migrationJobRepository = Objects.requireNonNull(migrationJobRepository, "MigrationJobRepository must not be null");
        this.migrationTaskRepository = Objects.requireNonNull(migrationTaskRepository, "MigrationTaskRepository must not be null");
    }

    /**
     * Executes an end-to-end migration job by ID.
     *
     * @param jobId unique migration job ID
     * @return updated MigrationJob in final status
     */
    public MigrationJob executeMigration(UUID jobId) {
        Objects.requireNonNull(jobId, "jobId must not be null");

        MigrationJob job = migrationJobRepository.findById(jobId)
                .orElseThrow(() -> new ResourceNotFoundException("Migration job not found with id: " + jobId));

        if (job.getStatus() == MigrationStatus.COMPLETED) {
            throw new InvalidStateException("Migration job has already completed: " + jobId);
        }

        if (job.getSourcePlatform() != Platform.SPOTIFY) {
            throw new InvalidStateException("Unsupported source platform for migration: " + job.getSourcePlatform());
        }

        if (job.getTargetPlatform() != Platform.YOUTUBE) {
            throw new InvalidStateException("Unsupported target platform for migration: " + job.getTargetPlatform());
        }

        UUID userId = job.getUser().getId();
        String sourcePlaylistId = job.getSourcePlaylistId();

        job.setStatus(MigrationStatus.IN_PROGRESS);
        job.setStartedAt(Instant.now());
        job = migrationJobRepository.save(job);

        MigrationExecutionSummary summary;
        try {
            summary = migratePlaylistInternal(job, userId, sourcePlaylistId, true);
        } catch (Exception ex) {
            String rawMsg = ex.getMessage() != null ? ex.getMessage() : ex.getClass().getSimpleName();
            String sanitizedMsg = sanitize(rawMsg);
            log.error("Migration job {} failed at playlist level: {}", jobId, sanitizedMsg);
            job.setStatus(MigrationStatus.FAILED);
            job.setCompletedAt(Instant.now());
            migrationJobRepository.save(job);
            throw new RuntimeException("Playlist migration failed: " + sanitizedMsg, ex);
        }

        job.setTotalTracks(summary.totalTracks());
        job.setProcessedTracks(summary.processedTracks());
        job.setFailedTracks(summary.failedTracks());
        job.setTargetPlaylistUrl(summary.destinationPlaylistUrl());
        job.setStatus(summary.finalStatus());
        job.setCompletedAt(Instant.now());

        return migrationJobRepository.save(job);
    }

    /**
     * Executes migration directly without existing job entity, producing an immutable execution summary.
     *
     * @param userId authenticated user ID
     * @param sourcePlaylistId Spotify source playlist ID
     * @param deduplicate whether to prevent duplicate candidate insertions
     * @return comprehensive MigrationExecutionSummary
     */
    public MigrationExecutionSummary migratePlaylist(UUID userId, String sourcePlaylistId, boolean deduplicate) {
        Objects.requireNonNull(userId, "userId must not be null");
        Objects.requireNonNull(sourcePlaylistId, "sourcePlaylistId must not be null");

        return migratePlaylistInternal(null, userId, sourcePlaylistId, deduplicate);
    }

    private MigrationExecutionSummary migratePlaylistInternal(
            MigrationJob job,
            UUID userId,
            String sourcePlaylistId,
            boolean deduplicate
    ) {
        long startTime = System.currentTimeMillis();

        // 1. Fetch Source Playlist Metadata
        PlaylistResponse sourcePlaylist = spotifyPlaylistService.getPlaylistMetadata(userId, sourcePlaylistId);
        String playlistTitle = sourcePlaylist.name() != null && !sourcePlaylist.name().isBlank()
                ? sourcePlaylist.name()
                : "Migrated Playlist";
        String playlistDesc = sourcePlaylist.description() != null ? sourcePlaylist.description() : "Migrated from Spotify";

        // 2. Create Target Playlist on YouTube
        CreateDestinationPlaylistRequest createRequest = new CreateDestinationPlaylistRequest(
                playlistTitle,
                playlistDesc,
                false
        );
        DestinationPlaylist destinationPlaylist = musicDestination.createPlaylist(userId, createRequest);

        // 3. Fetch Source Playlist Tracks (Order Preserved)
        List<TrackResponse> sourceTracks = spotifyPlaylistService.getPlaylistTracks(userId, sourcePlaylistId);
        int totalTracks = sourceTracks.size();

        List<TrackMigrationOutcome> outcomes = new ArrayList<>(totalTracks);
        Set<String> insertedCandidateIds = new HashSet<>();
        int processedCount = 0;
        int failedCount = 0;
        int duplicateCount = 0;
        int reviewRequiredCount = 0;

        for (int i = 0; i < sourceTracks.size(); i++) {
            TrackResponse track = sourceTracks.get(i);
            String sourceTrackId = track.externalId() != null ? track.externalId() : (track.id() != null ? track.id().toString() : "track-" + i);

            MigrationTask task = null;
            if (job != null) {
                task = new MigrationTask();
                task.setJob(job);
                task.setSourceTrackId(sourceTrackId);
                task.setStatus(MigrationStatus.IN_PROGRESS);
                task = migrationTaskRepository.save(task);
            }

            try {
                // Step A: Phase 5 Normalization
                Long durationMs = track.durationMs() != null ? track.durationMs().longValue() : null;
                RawTrackMetadata raw = RawTrackMetadata.of(track.name(), track.artist(), track.album(), durationMs);
                NormalizedTrackMetadata normalized = normalizationOrchestrator.normalize(raw);

                // Step B: Phase 6 Candidate Discovery
                CandidateDiscoveryRequest discoveryReq = new CandidateDiscoveryRequest(normalized, DiscoveryOptions.defaults());
                CandidateDiscoveryResult discoveryResult = candidateDiscoveryService.discoverCandidates(discoveryReq);

                // Step C: Phase 7 Matching Engine
                TrackMatchRequest matchReq = new TrackMatchRequest(sourceTrackId, normalized, discoveryResult.candidates());
                TrackMatchResult matchResult = matchingEngine.matchTrack(matchReq);

                // Step D: Decision Evaluation
                MatchDecision decision = matchResult.decision();
                Double overallScore = matchResult.score();
                Double calibratedProb = matchResult.calibratedProbability();

                if (decision == MatchDecision.MATCH && matchResult.hasSelectedCandidate() && matchResult.selectedCandidate() != null) {
                    String candidateId = matchResult.selectedCandidate().id();
                    String candidateTitle = matchResult.selectedCandidate().title();

                    if (deduplicate && insertedCandidateIds.contains(candidateId)) {
                        duplicateCount++;
                        failedCount++;
                        String dupMsg = "Duplicate: candidate '" + candidateId + "' already added to playlist";
                        if (task != null) {
                            task.setStatus(MigrationStatus.FAILED);
                            task.setErrorMessage(dupMsg);
                            migrationTaskRepository.save(task);
                        }
                        outcomes.add(new TrackMigrationOutcome(
                                i, sourceTrackId, track.name(), track.artist(),
                                candidateId, candidateTitle, decision, overallScore, calibratedProb,
                                MigrationStatus.FAILED, dupMsg
                        ));
                    } else {
                        // Insert track into destination
                        musicDestination.addTrackToPlaylist(
                                userId,
                                destinationPlaylist.id(),
                                new AddTrackToDestinationPlaylistRequest(candidateId, processedCount)
                        );
                        insertedCandidateIds.add(candidateId);
                        processedCount++;

                        if (task != null) {
                            task.setTargetTrackId(candidateId);
                            task.setStatus(MigrationStatus.COMPLETED);
                            migrationTaskRepository.save(task);
                        }
                        outcomes.add(new TrackMigrationOutcome(
                                i, sourceTrackId, track.name(), track.artist(),
                                candidateId, candidateTitle, decision, overallScore, calibratedProb,
                                MigrationStatus.COMPLETED, "Successfully matched and migrated"
                        ));
                    }
                } else if (decision == MatchDecision.REVIEW_REQUIRED) {
                    reviewRequiredCount++;
                    failedCount++;
                    String reviewMsg = "Review required: " + String.join("; ", matchResult.matchReasons());
                    if (task != null) {
                        task.setStatus(MigrationStatus.FAILED);
                        task.setErrorMessage(sanitize(reviewMsg));
                        migrationTaskRepository.save(task);
                    }
                    outcomes.add(new TrackMigrationOutcome(
                            i, sourceTrackId, track.name(), track.artist(),
                            null, null, decision, overallScore, calibratedProb,
                            MigrationStatus.FAILED, reviewMsg
                    ));
                } else {
                    // NO_MATCH
                    failedCount++;
                    String noMatchMsg = "No matching destination candidate found: " + String.join("; ", matchResult.matchReasons());
                    if (task != null) {
                        task.setStatus(MigrationStatus.FAILED);
                        task.setErrorMessage(sanitize(noMatchMsg));
                        migrationTaskRepository.save(task);
                    }
                    outcomes.add(new TrackMigrationOutcome(
                            i, sourceTrackId, track.name(), track.artist(),
                            null, null, decision, overallScore, calibratedProb,
                            MigrationStatus.FAILED, noMatchMsg
                    ));
                }
            } catch (Exception ex) {
                String rawMsg = ex.getMessage() != null ? ex.getMessage() : ex.getClass().getSimpleName();
                String sanitizedMsg = sanitize(rawMsg);
                log.warn("Migration failed for source track {}: {}", sourceTrackId, sanitizedMsg);
                failedCount++;
                String errorMsg = "Track processing failure: " + sanitizedMsg;
                if (task != null) {
                    task.setStatus(MigrationStatus.FAILED);
                    task.setErrorMessage(errorMsg);
                    migrationTaskRepository.save(task);
                }
                outcomes.add(new TrackMigrationOutcome(
                        i, sourceTrackId, track.name(), track.artist(),
                        null, null, null, null, null,
                        MigrationStatus.FAILED, errorMsg
                ));
            }
        }

        // 4. Determine Final Overall Status
        MigrationStatus finalStatus;
        if (totalTracks == 0) {
            finalStatus = MigrationStatus.COMPLETED;
        } else if (processedCount == totalTracks) {
            finalStatus = MigrationStatus.COMPLETED;
        } else if (processedCount > 0) {
            finalStatus = MigrationStatus.PARTIALLY_COMPLETED;
        } else {
            finalStatus = MigrationStatus.FAILED;
        }

        long durationMs = System.currentTimeMillis() - startTime;

        return new MigrationExecutionSummary(
                job != null ? job.getId() : null,
                userId,
                sourcePlaylistId,
                playlistTitle,
                destinationPlaylist.id(),
                destinationPlaylist.url(),
                finalStatus,
                totalTracks,
                processedCount,
                failedCount,
                duplicateCount,
                reviewRequiredCount,
                durationMs,
                outcomes
        );
    }

    public static String sanitize(String message) {
        if (message == null) {
            return null;
        }
        return TOKEN_PATTERN.matcher(message).replaceAll("$1[REDACTED]");
    }
}
