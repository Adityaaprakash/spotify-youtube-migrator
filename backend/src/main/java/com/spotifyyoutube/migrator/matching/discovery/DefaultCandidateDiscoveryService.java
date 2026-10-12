package com.spotifyyoutube.migrator.matching.discovery;

import com.spotifyyoutube.migrator.matching.normalization.NormalizedTrackMetadata;
import com.spotifyyoutube.migrator.migration.application.destination.MusicDestination;
import com.spotifyyoutube.migrator.migration.application.destination.model.DestinationSearchResult;
import com.spotifyyoutube.migrator.migration.application.destination.model.DestinationTrackCandidate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Default implementation of {@link CandidateDiscoveryService}.
 * Orchestrates deterministic search query generation, bounded provider dispatching,
 * candidate deduplication, and structured outcome synthesis.
 */
@Service
public class DefaultCandidateDiscoveryService implements CandidateDiscoveryService {

    private static final Logger log = LoggerFactory.getLogger(DefaultCandidateDiscoveryService.class);

    private final SearchQueryGenerator queryGenerator;
    private final MusicDestination musicDestination;

    public DefaultCandidateDiscoveryService(SearchQueryGenerator queryGenerator,
                                           MusicDestination musicDestination) {
        this.queryGenerator = Objects.requireNonNull(queryGenerator, "SearchQueryGenerator must not be null");
        this.musicDestination = Objects.requireNonNull(musicDestination, "MusicDestination must not be null");
    }

    @Override
    public CandidateDiscoveryResult discoverCandidates(CandidateDiscoveryRequest request) {
        Objects.requireNonNull(request, "CandidateDiscoveryRequest must not be null");
        NormalizedTrackMetadata trackMetadata = request.trackMetadata();
        DiscoveryOptions options = request.options();

        List<SearchQuery> queries = queryGenerator.generateQueries(trackMetadata, options);
        if (queries == null || queries.isEmpty()) {
            log.debug("No search queries generated for track: {}", trackMetadata.cleanTitle());
            return CandidateDiscoveryResult.empty(trackMetadata, List.of());
        }

        int maxQueriesToExecute = Math.min(queries.size(), options.maxQueries());
        int maxCandidates = options.maxCandidates();
        int resultsPerQuery = options.resultsPerQuery();

        List<SearchQueryExecution> executions = new ArrayList<>();
        List<DiscoveredCandidate> discoveredCandidates = new ArrayList<>();
        Set<String> seenCandidateIds = new LinkedHashSet<>();
        List<String> errorMessages = new ArrayList<>();

        for (int i = 0; i < maxQueriesToExecute; i++) {
            if (seenCandidateIds.size() >= maxCandidates) {
                log.debug("Candidate quota reached ({}/{}), skipping {} remaining queries",
                        seenCandidateIds.size(), maxCandidates, maxQueriesToExecute - i);
                break;
            }

            SearchQuery query = queries.get(i);
            try {
                DestinationSearchResult searchResult = musicDestination.searchTracks(
                        query.query(),
                        null,
                        resultsPerQuery
                );

                List<DestinationTrackCandidate> candidates = (searchResult != null && searchResult.candidates() != null)
                        ? searchResult.candidates()
                        : List.of();

                executions.add(SearchQueryExecution.success(query, candidates.size()));

                Set<String> seenInCurrentQuery = new HashSet<>();
                for (DestinationTrackCandidate candidate : candidates) {
                    if (candidate != null && candidate.id() != null && !candidate.id().isBlank()) {
                        String canonicalId = candidate.id().trim();
                        DestinationTrackCandidate canonicalCandidate = candidate.id().equals(canonicalId)
                                ? candidate
                                : new DestinationTrackCandidate(
                                        canonicalId,
                                        candidate.title(),
                                        candidate.channelOrArtist(),
                                        candidate.description(),
                                        candidate.thumbnailUrl(),
                                        candidate.publishedAt(),
                                        candidate.duration()
                                );
                        if (!seenInCurrentQuery.add(canonicalId)) {
                            continue;
                        }
                        if (seenCandidateIds.contains(canonicalId)) {
                            discoveredCandidates.add(new DiscoveredCandidate(canonicalCandidate, query));
                        } else if (seenCandidateIds.size() < maxCandidates) {
                            seenCandidateIds.add(canonicalId);
                            discoveredCandidates.add(new DiscoveredCandidate(canonicalCandidate, query));
                        }
                    }
                }
            } catch (Exception ex) {
                String rawMsg = (ex.getMessage() != null && !ex.getMessage().isBlank())
                        ? ex.getMessage()
                        : ex.getClass().getSimpleName();
                String errorMsg = sanitizeErrorMessage(rawMsg);
                log.warn("Search query failed for '{}' [strategy={}]: {}", query.query(), query.strategy(), errorMsg);
                executions.add(SearchQueryExecution.failure(query, errorMsg));
                errorMessages.add(errorMsg);
            }
        }

        boolean hasCandidates = !seenCandidateIds.isEmpty();
        boolean hasFailures = !errorMessages.isEmpty();

        if (hasCandidates && !hasFailures) {
            return CandidateDiscoveryResult.success(trackMetadata, discoveredCandidates, executions);
        } else if (!hasCandidates && !hasFailures) {
            return CandidateDiscoveryResult.empty(trackMetadata, executions);
        } else if (hasCandidates && hasFailures) {
            String combinedError = String.join("; ", errorMessages);
            return CandidateDiscoveryResult.partialFailure(trackMetadata, discoveredCandidates, executions, combinedError);
        } else {
            String combinedError = String.join("; ", errorMessages);
            return CandidateDiscoveryResult.failure(trackMetadata, executions, combinedError);
        }
    }

    private String sanitizeErrorMessage(String rawMessage) {
        if (rawMessage == null || rawMessage.isBlank()) {
            return "Unknown error";
        }
        return rawMessage
                .replaceAll("(?i)(access_token|client_secret|api_key|key|token|secret|password)=[^&\\s\"';,]+", "$1=***")
                .replaceAll("(?i)bearer\\s+[A-Za-z0-9._~+/-]+", "Bearer ***")
                .replaceAll("(?i)basic\\s+[A-Za-z0-9+/=]+", "Basic ***")
                .replaceAll("(?i)(authorization:\\s*)(?!bearer|basic|\\*\\*\\*)[^\\s\"';,]+", "$1***");
    }
}
