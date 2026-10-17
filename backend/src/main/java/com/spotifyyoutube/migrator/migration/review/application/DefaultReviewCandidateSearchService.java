package com.spotifyyoutube.migrator.migration.review.application;

import com.spotifyyoutube.migrator.common.exception.ExternalProviderException;
import com.spotifyyoutube.migrator.common.exception.InvalidStateException;
import com.spotifyyoutube.migrator.common.exception.ResourceNotFoundException;
import com.spotifyyoutube.migrator.matching.discovery.CandidateDiscoveryRequest;
import com.spotifyyoutube.migrator.matching.discovery.CandidateDiscoveryResult;
import com.spotifyyoutube.migrator.matching.discovery.CandidateDiscoveryService;
import com.spotifyyoutube.migrator.matching.discovery.DiscoveryOptions;
import com.spotifyyoutube.migrator.matching.discovery.SearchQueryStrategy;
import com.spotifyyoutube.migrator.matching.normalization.NormalizationOrchestrator;
import com.spotifyyoutube.migrator.matching.normalization.NormalizedTrackMetadata;
import com.spotifyyoutube.migrator.matching.normalization.RawTrackMetadata;
import com.spotifyyoutube.migrator.migration.application.destination.MusicDestination;
import com.spotifyyoutube.migrator.migration.application.destination.model.DestinationSearchResult;
import com.spotifyyoutube.migrator.migration.application.destination.model.DestinationTrackCandidate;
import com.spotifyyoutube.migrator.migration.review.domain.ReviewSession;
import com.spotifyyoutube.migrator.migration.review.domain.ReviewTrack;
import com.spotifyyoutube.migrator.migration.review.repository.ReviewSessionRepository;
import com.spotifyyoutube.migrator.migration.review.repository.ReviewTrackRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Default implementation of {@link ReviewCandidateSearchService}.
 * <p>
 * Coordinates manual candidate searches for tracks under review.
 * Enforces:
 * <ol>
 *   <li>Session ownership and in-progress status validation.</li>
 *   <li>Bounded external API quota usage (capped queries, results, and pagination).</li>
 *   <li>Encounter-order deduplication and defensive candidate sanitization.</li>
 *   <li>Strict read-only safety: zero mutation of review sessions, review tracks, Phase 9 plans, or destination playlists.</li>
 *   <li>Proper error distinction: quota exhaustion is never masked as an empty result.</li>
 * </ol>
 */
@Service
@Transactional(readOnly = true)
public class DefaultReviewCandidateSearchService implements ReviewCandidateSearchService {

    private static final Logger log = LoggerFactory.getLogger(DefaultReviewCandidateSearchService.class);

    private final ReviewSessionRepository reviewSessionRepository;
    private final ReviewTrackRepository reviewTrackRepository;
    private final NormalizationOrchestrator normalizationOrchestrator;
    private final CandidateDiscoveryService candidateDiscoveryService;
    private final MusicDestination musicDestination;
    private final ReviewSearchProperties properties;

    public DefaultReviewCandidateSearchService(
            ReviewSessionRepository reviewSessionRepository,
            ReviewTrackRepository reviewTrackRepository,
            NormalizationOrchestrator normalizationOrchestrator,
            CandidateDiscoveryService candidateDiscoveryService,
            MusicDestination musicDestination,
            ReviewSearchProperties properties
    ) {
        this.reviewSessionRepository = Objects.requireNonNull(reviewSessionRepository, "ReviewSessionRepository must not be null");
        this.reviewTrackRepository = Objects.requireNonNull(reviewTrackRepository, "ReviewTrackRepository must not be null");
        this.normalizationOrchestrator = Objects.requireNonNull(normalizationOrchestrator, "NormalizationOrchestrator must not be null");
        this.candidateDiscoveryService = Objects.requireNonNull(candidateDiscoveryService, "CandidateDiscoveryService must not be null");
        this.musicDestination = Objects.requireNonNull(musicDestination, "MusicDestination must not be null");
        this.properties = Objects.requireNonNull(properties, "ReviewSearchProperties must not be null");
    }

    @Override
    public ReviewCandidateSearchResult searchCandidates(UUID sessionId, int position) {
        return searchCandidates(sessionId, position, ReviewCandidateSearchRequest.defaultSearch(), null);
    }

    @Override
    public ReviewCandidateSearchResult searchCandidates(UUID sessionId, int position, ReviewCandidateSearchRequest request) {
        return searchCandidates(sessionId, position, request, null);
    }

    @Override
    public ReviewCandidateSearchResult searchCandidates(
            UUID sessionId,
            int position,
            ReviewCandidateSearchRequest request,
            UUID userId
    ) {
        Objects.requireNonNull(sessionId, "sessionId must not be null");
        if (position < 0) {
            throw new IllegalArgumentException("position must not be negative: " + position);
        }
        ReviewCandidateSearchRequest req = request != null ? request : ReviewCandidateSearchRequest.defaultSearch();

        // 1. Session lookup and validation
        ReviewSession session = reviewSessionRepository.findById(sessionId)
                .orElseThrow(() -> new ResourceNotFoundException("Review session not found with id: " + sessionId));

        // 2. User ownership validation
        if (userId != null && !Objects.equals(session.getUser().getId(), userId)) {
            throw new ResourceNotFoundException("Review session not found with id: " + sessionId + " for user: " + userId);
        }

        // 3. Status invariant check (must be IN_PROGRESS)
        if (!session.isInProgress()) {
            throw new InvalidStateException("Cannot search candidates: review session is in status " + session.getStatus());
        }

        // 4. Track lookup and association validation
        ReviewTrack track = session.getTracks().stream()
                .filter(t -> t.getPosition() == position)
                .findFirst()
                .orElseGet(() -> reviewTrackRepository.findBySessionIdAndPosition(sessionId, position)
                        .orElseThrow(() -> new ResourceNotFoundException(String.format(
                                "Track not found at position %d in review session %s", position, sessionId
                        ))));

        // 5. Determine effective limit
        int effectiveLimit = req.limit() != null ? req.limit() : properties.getDefaultMaxResults();
        if (effectiveLimit < properties.getMinResultsLimit() || effectiveLimit > properties.getMaxResultsLimit()) {
            throw new IllegalArgumentException(String.format(
                    "limit must be between %d and %d", properties.getMinResultsLimit(), properties.getMaxResultsLimit()
            ));
        }

        // 6. Branch on explicit reviewer query vs automated query formulation
        if (req.isCustomQuery()) {
            return executeCustomQuerySearch(session, track, req.query(), effectiveLimit);
        } else {
            return executeMetadataSearch(session, track, effectiveLimit);
        }
    }

    private ReviewCandidateSearchResult executeCustomQuerySearch(
            ReviewSession session,
            ReviewTrack track,
            String rawQuery,
            int limit
    ) {
        String trimmedQuery = rawQuery.trim();
        if (trimmedQuery.length() > properties.getMaxQueryLength()) {
            throw new IllegalArgumentException("Search query exceeds maximum limit of "
                    + properties.getMaxQueryLength() + " characters");
        }

        try {
            DestinationSearchResult searchResult = musicDestination.searchTracks(trimmedQuery, null, limit);
            List<DestinationTrackCandidate> rawCandidates = (searchResult != null && searchResult.candidates() != null)
                    ? searchResult.candidates()
                    : List.of();

            List<DestinationTrackCandidate> filtered = sanitizeAndDeduplicate(rawCandidates, limit);
            log.debug("Manual search for track at pos {} in session {} with query '{}' returned {} candidates",
                    track.getPosition(), session.getId(), trimmedQuery, filtered.size());

            return new ReviewCandidateSearchResult(
                    session.getId(),
                    track.getPosition(),
                    track.getSourceTrackId(),
                    track.getSourceTitle(),
                    track.getSourceArtist(),
                    trimmedQuery,
                    filtered,
                    true,
                    filtered.size()
            );
        } catch (ExternalProviderException ex) {
            String sanitizedMessage = sanitizeErrorMessage(ex.getMessage());
            log.warn("Manual candidate search failed for query '{}': {}", trimmedQuery, sanitizedMessage);
            throw new ExternalProviderException(sanitizedMessage, ex.getProviderStatusCode(), ex.getRetryAfter());
        } catch (Exception ex) {
            String sanitizedMessage = sanitizeErrorMessage(ex.getMessage());
            log.warn("Manual candidate search unexpected error for query '{}': {}", trimmedQuery, sanitizedMessage);
            throw new ExternalProviderException(sanitizedMessage, 502);
        }
    }

    private ReviewCandidateSearchResult executeMetadataSearch(
            ReviewSession session,
            ReviewTrack track,
            int limit
    ) {
        RawTrackMetadata raw = RawTrackMetadata.builder()
                .title(track.getSourceTitle())
                .artistCredit(track.getSourceArtist())
                .build();
        NormalizedTrackMetadata normalized = normalizationOrchestrator.normalize(raw);

        DiscoveryOptions discoveryOptions = new DiscoveryOptions(
                limit,
                properties.getMaxQueriesPerTrack(),
                limit,
                Set.of(SearchQueryStrategy.values())
        );

        try {
            CandidateDiscoveryResult discoveryResult = candidateDiscoveryService.discoverCandidates(
                    new CandidateDiscoveryRequest(normalized, discoveryOptions)
            );

            if (discoveryResult == null || discoveryResult.isEmpty()) {
                String queryUsed = resolveQueryUsed(discoveryResult, normalized);
                return ReviewCandidateSearchResult.empty(
                        session.getId(),
                        track.getPosition(),
                        track.getSourceTrackId(),
                        track.getSourceTitle(),
                        track.getSourceArtist(),
                        queryUsed,
                        false
                );
            }

            if (discoveryResult.isFailed()) {
                String sanitizedError = sanitizeErrorMessage(discoveryResult.errorMessage());
                log.warn("Candidate discovery failed for track at pos {} in session {}: {}",
                        track.getPosition(), session.getId(), sanitizedError);
                throw new ExternalProviderException(sanitizedError, 403);
            }

            List<DestinationTrackCandidate> rawCandidates = discoveryResult.candidates();
            List<DestinationTrackCandidate> filtered = sanitizeAndDeduplicate(rawCandidates, limit);
            String queryUsed = resolveQueryUsed(discoveryResult, normalized);

            return new ReviewCandidateSearchResult(
                    session.getId(),
                    track.getPosition(),
                    track.getSourceTrackId(),
                    track.getSourceTitle(),
                    track.getSourceArtist(),
                    queryUsed,
                    filtered,
                    false,
                    filtered.size()
            );
        } catch (ExternalProviderException ex) {
            String sanitizedMessage = sanitizeErrorMessage(ex.getMessage());
            log.warn("Candidate discovery provider error for track at pos {} in session {}: {}",
                    track.getPosition(), session.getId(), sanitizedMessage);
            throw new ExternalProviderException(sanitizedMessage, ex.getProviderStatusCode(), ex.getRetryAfter());
        }
    }

    private String resolveQueryUsed(CandidateDiscoveryResult discoveryResult, NormalizedTrackMetadata normalized) {
        if (discoveryResult != null && discoveryResult.executedQueries() != null && !discoveryResult.executedQueries().isEmpty()) {
            return discoveryResult.executedQueries().get(0).query().query();
        }
        String cleanTitle = normalized != null ? normalized.cleanTitle() : "";
        String primaryArtist = normalized != null && normalized.artistCredit() != null ? normalized.artistCredit().primaryArtist() : "";
        return (primaryArtist + " " + cleanTitle).trim();
    }

    private List<DestinationTrackCandidate> sanitizeAndDeduplicate(
            List<DestinationTrackCandidate> rawCandidates,
            int maxResults
    ) {
        if (rawCandidates == null || rawCandidates.isEmpty()) {
            return List.of();
        }
        Map<String, DestinationTrackCandidate> unique = new LinkedHashMap<>();
        for (DestinationTrackCandidate c : rawCandidates) {
            if (unique.size() >= maxResults) {
                break;
            }
            if (c == null || c.id() == null || c.id().isBlank()) {
                continue;
            }
            String canonicalId = c.id().trim();
            if (c.duration() != null && c.duration().isNegative()) {
                continue;
            }
            DestinationTrackCandidate canonicalCandidate = c.id().equals(canonicalId)
                    ? c
                    : new DestinationTrackCandidate(
                            canonicalId,
                            c.title(),
                            c.channelOrArtist(),
                            c.description(),
                            c.thumbnailUrl(),
                            c.publishedAt(),
                            c.duration()
                    );
            unique.putIfAbsent(canonicalId, canonicalCandidate);
        }
        return List.copyOf(unique.values());
    }

    private String sanitizeErrorMessage(String rawMessage) {
        if (rawMessage == null || rawMessage.isBlank()) {
            return "External provider error occurred";
        }
        return rawMessage
                .replaceAll("(?i)(access_token|client_secret|api_key|key|token|secret|password)=[^&\\s\"';,]+", "$1=***")
                .replaceAll("(?i)bearer\\s+[A-Za-z0-9._~+/-]+", "Bearer ***")
                .replaceAll("(?i)basic\\s+[A-Za-z0-9+/=]+", "Basic ***")
                .replaceAll("(?i)(authorization:\\s*)(?!bearer|basic|\\*\\*\\*)[^\\s\"';,]+", "$1***");
    }
}
