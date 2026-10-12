package com.spotifyyoutube.migrator.matching.discovery;

import com.spotifyyoutube.migrator.matching.normalization.NormalizedArtistCredit;
import com.spotifyyoutube.migrator.matching.normalization.NormalizedTrackMetadata;
import com.spotifyyoutube.migrator.matching.normalization.RawTrackMetadata;
import com.spotifyyoutube.migrator.matching.normalization.TokenizedTitle;
import com.spotifyyoutube.migrator.migration.application.destination.model.DestinationTrackCandidate;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CandidateDiscoveryResultTest {

    private NormalizedTrackMetadata sampleSourceTrack() {
        return new NormalizedTrackMetadata(
                RawTrackMetadata.of("Get Lucky", "Daft Punk", "Random Access Memories", 248000L),
                "Get Lucky",
                new NormalizedArtistCredit("Daft Punk", List.of("Pharrell Williams")),
                List.of("Daft Punk", "Pharrell Williams"),
                List.of("Pharrell Williams"),
                "Random Access Memories",
                Duration.ofMillis(248000),
                new TokenizedTitle("Get Lucky", "Get Lucky", false, Set.of(), null, null, Set.of(), List.of(), List.of(), List.of())
        );
    }

    private DestinationTrackCandidate candidate(String id, String title) {
        return new DestinationTrackCandidate(
                id,
                title,
                "Daft Punk",
                "Description for " + title,
                "https://i.ytimg.com/vi/" + id + "/default.jpg",
                "2013-04-19T00:00:00Z"
        );
    }

    private SearchQuery query(String query, SearchQueryStrategy strategy) {
        return new SearchQuery(query, strategy);
    }

    @Test
    @DisplayName("Success factory creates result with deduplicated candidates preserving encounter order")
    void successFactoryDeduplicatesPreservingOrder() {
        NormalizedTrackMetadata track = sampleSourceTrack();
        SearchQuery q1 = query("Daft Punk - Get Lucky", SearchQueryStrategy.PRIMARY_TITLE_ARTIST);
        SearchQuery q2 = query("Daft Punk Get Lucky feat Pharrell", SearchQueryStrategy.TITLE_ARTIST_FEATURED);

        DestinationTrackCandidate c1 = candidate("vid1", "Get Lucky (Official Video)");
        DestinationTrackCandidate c2 = candidate("vid2", "Get Lucky (Radio Edit)");
        DestinationTrackCandidate cDuplicate = candidate("vid1", "Get Lucky (Official Video Duplicate)");

        List<DiscoveredCandidate> discovered = List.of(
                new DiscoveredCandidate(c1, q1),
                new DiscoveredCandidate(c2, q1),
                new DiscoveredCandidate(cDuplicate, q2)
        );

        List<SearchQueryExecution> executions = List.of(
                SearchQueryExecution.success(q1, 2),
                SearchQueryExecution.success(q2, 1)
        );

        CandidateDiscoveryResult result = CandidateDiscoveryResult.success(track, discovered, executions);

        assertThat(result.status()).isEqualTo(CandidateDiscoveryStatus.SUCCESS);
        assertThat(result.isSuccessful()).isTrue();
        assertThat(result.hasCandidates()).isTrue();
        assertThat(result.isEmpty()).isFalse();
        assertThat(result.isFailed()).isFalse();
        assertThat(result.candidateCount()).isEqualTo(2);

        // Deduplication checked: only vid1 and vid2 present, in encounter order
        assertThat(result.candidates()).containsExactly(c1, c2);
        // All discovered items preserved for provenance
        assertThat(result.discoveredCandidates()).hasSize(3);
        assertThat(result.executedQueries()).containsExactlyElementsOf(executions);
        assertThat(result.errorMessage()).isNull();
    }

    @Test
    @DisplayName("Empty factory creates result with EMPTY status and zero candidates")
    void emptyFactoryCreatesEmptyResult() {
        NormalizedTrackMetadata track = sampleSourceTrack();
        SearchQuery q1 = query("Daft Punk - Get Lucky", SearchQueryStrategy.PRIMARY_TITLE_ARTIST);
        List<SearchQueryExecution> executions = List.of(SearchQueryExecution.success(q1, 0));

        CandidateDiscoveryResult result = CandidateDiscoveryResult.empty(track, executions);

        assertThat(result.status()).isEqualTo(CandidateDiscoveryStatus.EMPTY);
        assertThat(result.isEmpty()).isTrue();
        assertThat(result.isSuccessful()).isFalse();
        assertThat(result.hasCandidates()).isFalse();
        assertThat(result.isFailed()).isFalse();
        assertThat(result.candidateCount()).isZero();
        assertThat(result.candidates()).isEmpty();
        assertThat(result.discoveredCandidates()).isEmpty();
        assertThat(result.errorMessage()).isNull();
    }

    @Test
    @DisplayName("PartialFailure factory preserves candidates from successful query and marks PARTIAL_FAILURE")
    void partialFailureFactoryPreservesCandidates() {
        NormalizedTrackMetadata track = sampleSourceTrack();
        SearchQuery q1 = query("Daft Punk - Get Lucky", SearchQueryStrategy.PRIMARY_TITLE_ARTIST);
        SearchQuery q2 = query("Daft Punk Get Lucky Album", SearchQueryStrategy.TITLE_ARTIST_ALBUM);

        DestinationTrackCandidate c1 = candidate("vid1", "Get Lucky (Official Video)");
        List<DiscoveredCandidate> discovered = List.of(new DiscoveredCandidate(c1, q1));

        List<SearchQueryExecution> executions = List.of(
                SearchQueryExecution.success(q1, 1),
                SearchQueryExecution.failure(q2, "HTTP 403 Rate Limit")
        );

        CandidateDiscoveryResult result = CandidateDiscoveryResult.partialFailure(
                track, discovered, executions, "Secondary query failed: HTTP 403 Rate Limit"
        );

        assertThat(result.status()).isEqualTo(CandidateDiscoveryStatus.PARTIAL_FAILURE);
        assertThat(result.isSuccessful()).isTrue();
        assertThat(result.hasCandidates()).isTrue();
        assertThat(result.isEmpty()).isFalse();
        assertThat(result.isFailed()).isFalse();
        assertThat(result.candidateCount()).isEqualTo(1);
        assertThat(result.candidates()).containsExactly(c1);
        assertThat(result.errorMessage()).isEqualTo("Secondary query failed: HTTP 403 Rate Limit");
    }

    @Test
    @DisplayName("Failure factory creates result with FAILED status and stores error message")
    void failureFactoryCreatesFailedResult() {
        NormalizedTrackMetadata track = sampleSourceTrack();
        SearchQuery q1 = query("Daft Punk - Get Lucky", SearchQueryStrategy.PRIMARY_TITLE_ARTIST);
        List<SearchQueryExecution> executions = List.of(
                SearchQueryExecution.failure(q1, "API Key Quota Exceeded")
        );

        CandidateDiscoveryResult result = CandidateDiscoveryResult.failure(track, executions, "API Key Quota Exceeded");

        assertThat(result.status()).isEqualTo(CandidateDiscoveryStatus.FAILED);
        assertThat(result.isFailed()).isTrue();
        assertThat(result.isSuccessful()).isFalse();
        assertThat(result.hasCandidates()).isFalse();
        assertThat(result.isEmpty()).isFalse();
        assertThat(result.candidateCount()).isZero();
        assertThat(result.candidates()).isEmpty();
        assertThat(result.errorMessage()).isEqualTo("API Key Quota Exceeded");
    }

    @Test
    @DisplayName("Lists are immutable and throw UnsupportedOperationException on modification")
    void listsAreImmutable() {
        NormalizedTrackMetadata track = sampleSourceTrack();
        CandidateDiscoveryResult result = CandidateDiscoveryResult.empty(track, List.of());

        assertThatThrownBy(() -> result.candidates().add(candidate("id", "title")))
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> result.discoveredCandidates().add(new DiscoveredCandidate(candidate("id", "title"), query("q", SearchQueryStrategy.PRIMARY_TITLE_ARTIST))))
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> result.executedQueries().add(SearchQueryExecution.success(query("q", SearchQueryStrategy.PRIMARY_TITLE_ARTIST), 0)))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    @DisplayName("Throws NullPointerException when sourceTrack or status is null")
    void throwsOnNullRequiredFields() {
        NormalizedTrackMetadata track = sampleSourceTrack();

        assertThatThrownBy(() -> new CandidateDiscoveryResult(null, List.of(), List.of(), List.of(), CandidateDiscoveryStatus.SUCCESS, null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("NormalizedTrackMetadata must not be null");

        assertThatThrownBy(() -> new CandidateDiscoveryResult(track, List.of(), List.of(), List.of(), null, null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("CandidateDiscoveryStatus must not be null");
    }

    @Test
    @DisplayName("Deduplication handles whitespace-padded video IDs and empty IDs safely")
    void deduplicationHandlesWhitespaceAndEmptyIds() {
        NormalizedTrackMetadata track = sampleSourceTrack();
        SearchQuery q1 = query("Query", SearchQueryStrategy.PRIMARY_TITLE_ARTIST);

        DestinationTrackCandidate c1 = candidate("vid1", "Title 1");
        // Simulated candidate with whitespace or empty
        DestinationTrackCandidate c1Padded = new DestinationTrackCandidate(
                " vid1 ", "Title 1 Variant", "Artist", "Desc", "url", "2023", Duration.ofMinutes(3)
        );
        DestinationTrackCandidate cBlank = new DestinationTrackCandidate(
                "", "Blank ID", "Artist", "Desc", "url", "2023", Duration.ofMinutes(3)
        );

        List<DiscoveredCandidate> discovered = List.of(
                new DiscoveredCandidate(c1, q1),
                new DiscoveredCandidate(c1Padded, q1),
                new DiscoveredCandidate(cBlank, q1)
        );

        CandidateDiscoveryResult result = CandidateDiscoveryResult.success(track, discovered, List.of());

        assertThat(result.candidates()).containsExactly(c1);
    }
}
