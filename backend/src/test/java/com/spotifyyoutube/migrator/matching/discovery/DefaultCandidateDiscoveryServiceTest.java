package com.spotifyyoutube.migrator.matching.discovery;

import com.spotifyyoutube.migrator.common.exception.ExternalProviderException;
import com.spotifyyoutube.migrator.common.exception.ResourceNotFoundException;
import com.spotifyyoutube.migrator.matching.normalization.NormalizedArtistCredit;
import com.spotifyyoutube.migrator.matching.normalization.NormalizedTrackMetadata;
import com.spotifyyoutube.migrator.matching.normalization.RawTrackMetadata;
import com.spotifyyoutube.migrator.matching.normalization.TokenizedTitle;
import com.spotifyyoutube.migrator.migration.application.destination.MusicDestination;
import com.spotifyyoutube.migrator.migration.application.destination.model.DestinationSearchResult;
import com.spotifyyoutube.migrator.migration.application.destination.model.DestinationTrackCandidate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Duration;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DefaultCandidateDiscoveryServiceTest {

    @Mock
    private SearchQueryGenerator queryGenerator;

    @Mock
    private MusicDestination musicDestination;

    private DefaultCandidateDiscoveryService discoveryService;

    @BeforeEach
    void setUp() {
        discoveryService = new DefaultCandidateDiscoveryService(queryGenerator, musicDestination);
    }

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
                "Daft Punk Official",
                "Description for " + title,
                "https://i.ytimg.com/vi/" + id + "/default.jpg",
                "2013-04-19T00:00:00Z"
        );
    }

    private SearchQuery query(String text, SearchQueryStrategy strategy, int priority) {
        return new SearchQuery(text, strategy, priority);
    }

    @Nested
    @DisplayName("Constructor and Request Validation")
    class ValidationTests {

        @Test
        @DisplayName("Constructor throws NullPointerException when SearchQueryGenerator is null")
        void constructorThrowsOnNullQueryGenerator() {
            assertThatThrownBy(() -> new DefaultCandidateDiscoveryService(null, musicDestination))
                    .isInstanceOf(NullPointerException.class)
                    .hasMessageContaining("SearchQueryGenerator must not be null");
        }

        @Test
        @DisplayName("Constructor throws NullPointerException when MusicDestination is null")
        void constructorThrowsOnNullMusicDestination() {
            assertThatThrownBy(() -> new DefaultCandidateDiscoveryService(queryGenerator, null))
                    .isInstanceOf(NullPointerException.class)
                    .hasMessageContaining("MusicDestination must not be null");
        }

        @Test
        @DisplayName("discoverCandidates throws NullPointerException when request is null")
        void throwsOnNullRequest() {
            assertThatThrownBy(() -> discoveryService.discoverCandidates((CandidateDiscoveryRequest) null))
                    .isInstanceOf(NullPointerException.class)
                    .hasMessageContaining("CandidateDiscoveryRequest must not be null");
        }

        @Test
        @DisplayName("Convenience overload with NormalizedTrackMetadata uses default options")
        void convenienceOverloadUsesDefaults() {
            NormalizedTrackMetadata track = sampleSourceTrack();
            when(queryGenerator.generateQueries(eq(track), any(DiscoveryOptions.class)))
                    .thenReturn(List.of());

            CandidateDiscoveryResult result = discoveryService.discoverCandidates(track);

            assertThat(result).isNotNull();
            assertThat(result.status()).isEqualTo(CandidateDiscoveryStatus.EMPTY);
            verify(queryGenerator).generateQueries(eq(track), eq(DiscoveryOptions.defaults()));
        }

        @Test
        @DisplayName("Convenience overload with custom DiscoveryOptions delegates properly")
        void convenienceOverloadWithCustomOptions() {
            NormalizedTrackMetadata track = sampleSourceTrack();
            DiscoveryOptions customOptions = new DiscoveryOptions(5, 1, 5, Set.of(SearchQueryStrategy.PRIMARY_TITLE_ARTIST));
            when(queryGenerator.generateQueries(track, customOptions))
                    .thenReturn(List.of());

            CandidateDiscoveryResult result = discoveryService.discoverCandidates(track, customOptions);

            assertThat(result).isNotNull();
            assertThat(result.status()).isEqualTo(CandidateDiscoveryStatus.EMPTY);
            verify(queryGenerator).generateQueries(track, customOptions);
        }
    }

    @Nested
    @DisplayName("Empty Query Generation")
    class EmptyQueryGenerationTests {

        @Test
        @DisplayName("Returns EMPTY result with 0 executions when query generator returns empty list")
        void returnsEmptyWhenNoQueriesGenerated() {
            NormalizedTrackMetadata track = sampleSourceTrack();
            DiscoveryOptions options = DiscoveryOptions.defaults();
            when(queryGenerator.generateQueries(track, options)).thenReturn(List.of());

            CandidateDiscoveryResult result = discoveryService.discoverCandidates(new CandidateDiscoveryRequest(track, options));

            assertThat(result.status()).isEqualTo(CandidateDiscoveryStatus.EMPTY);
            assertThat(result.isEmpty()).isTrue();
            assertThat(result.hasCandidates()).isFalse();
            assertThat(result.candidates()).isEmpty();
            assertThat(result.discoveredCandidates()).isEmpty();
            assertThat(result.executedQueries()).isEmpty();
            assertThat(result.errorMessage()).isNull();
            verifyNoInteractions(musicDestination);
        }

        @Test
        @DisplayName("Returns EMPTY result when query generator returns null")
        void returnsEmptyWhenQueryGeneratorReturnsNull() {
            NormalizedTrackMetadata track = sampleSourceTrack();
            DiscoveryOptions options = DiscoveryOptions.defaults();
            when(queryGenerator.generateQueries(track, options)).thenReturn(null);

            CandidateDiscoveryResult result = discoveryService.discoverCandidates(new CandidateDiscoveryRequest(track, options));

            assertThat(result.status()).isEqualTo(CandidateDiscoveryStatus.EMPTY);
            assertThat(result.executedQueries()).isEmpty();
            verifyNoInteractions(musicDestination);
        }
    }

    @Nested
    @DisplayName("Case 1: All Queries Succeed and Produce Results (SUCCESS)")
    class Case1AllQueriesSucceedWithResults {

        @Test
        @DisplayName("Single query succeeds and returns candidates")
        void singleQuerySucceeds() {
            NormalizedTrackMetadata track = sampleSourceTrack();
            DiscoveryOptions options = new DiscoveryOptions(10, 1, 10, null);
            SearchQuery q1 = query("Daft Punk - Get Lucky", SearchQueryStrategy.PRIMARY_TITLE_ARTIST, 1);
            when(queryGenerator.generateQueries(track, options)).thenReturn(List.of(q1));

            DestinationTrackCandidate c1 = candidate("vid1", "Get Lucky (Official Video)");
            DestinationTrackCandidate c2 = candidate("vid2", "Get Lucky (Audio)");
            when(musicDestination.searchTracks("Daft Punk - Get Lucky", null, 10))
                    .thenReturn(new DestinationSearchResult(List.of(c1, c2), null, 2));

            CandidateDiscoveryResult result = discoveryService.discoverCandidates(new CandidateDiscoveryRequest(track, options));

            assertThat(result.status()).isEqualTo(CandidateDiscoveryStatus.SUCCESS);
            assertThat(result.isSuccessful()).isTrue();
            assertThat(result.hasCandidates()).isTrue();
            assertThat(result.candidateCount()).isEqualTo(2);
            assertThat(result.candidates()).containsExactly(c1, c2);
            assertThat(result.discoveredCandidates()).hasSize(2);
            assertThat(result.discoveredCandidates().get(0)).isEqualTo(new DiscoveredCandidate(c1, q1));
            assertThat(result.discoveredCandidates().get(1)).isEqualTo(new DiscoveredCandidate(c2, q1));
            assertThat(result.executedQueries()).containsExactly(SearchQueryExecution.success(q1, 2));
            assertThat(result.errorMessage()).isNull();
        }

        @Test
        @DisplayName("Multiple queries succeed and accumulate unique candidates preserving encounter order")
        void multipleQueriesSucceedAndAccumulate() {
            NormalizedTrackMetadata track = sampleSourceTrack();
            DiscoveryOptions options = new DiscoveryOptions(10, 2, 5, null);
            SearchQuery q1 = query("Daft Punk - Get Lucky", SearchQueryStrategy.PRIMARY_TITLE_ARTIST, 1);
            SearchQuery q2 = query("Daft Punk feat Pharrell - Get Lucky", SearchQueryStrategy.TITLE_ARTIST_FEATURED, 2);
            when(queryGenerator.generateQueries(track, options)).thenReturn(List.of(q1, q2));

            DestinationTrackCandidate c1 = candidate("vid1", "Get Lucky");
            DestinationTrackCandidate c2 = candidate("vid2", "Get Lucky Radio Edit");
            DestinationTrackCandidate c3 = candidate("vid3", "Get Lucky feat Pharrell");

            when(musicDestination.searchTracks("Daft Punk - Get Lucky", null, 5))
                    .thenReturn(new DestinationSearchResult(List.of(c1, c2), null, 2));
            when(musicDestination.searchTracks("Daft Punk feat Pharrell - Get Lucky", null, 5))
                    .thenReturn(new DestinationSearchResult(List.of(c3), null, 1));

            CandidateDiscoveryResult result = discoveryService.discoverCandidates(new CandidateDiscoveryRequest(track, options));

            assertThat(result.status()).isEqualTo(CandidateDiscoveryStatus.SUCCESS);
            assertThat(result.candidates()).containsExactly(c1, c2, c3);
            assertThat(result.discoveredCandidates()).hasSize(3);
            assertThat(result.executedQueries()).containsExactly(
                    SearchQueryExecution.success(q1, 2),
                    SearchQueryExecution.success(q2, 1)
            );
        }
    }

    @Nested
    @DisplayName("Case 2: All Queries Succeed But Produce Zero Results (EMPTY)")
    class Case2AllQueriesSucceedWithZeroResults {

        @Test
        @DisplayName("All queries return empty candidates list results in EMPTY status")
        void allQueriesReturnZeroCandidates() {
            NormalizedTrackMetadata track = sampleSourceTrack();
            DiscoveryOptions options = new DiscoveryOptions(10, 2, 10, null);
            SearchQuery q1 = query("Obscure Artist - Rare Track", SearchQueryStrategy.PRIMARY_TITLE_ARTIST, 1);
            SearchQuery q2 = query("Obscure Artist - Rare Track 1999", SearchQueryStrategy.TITLE_ARTIST_ALBUM, 2);
            when(queryGenerator.generateQueries(track, options)).thenReturn(List.of(q1, q2));

            when(musicDestination.searchTracks(q1.query(), null, 10))
                    .thenReturn(new DestinationSearchResult(List.of(), null, 0));
            when(musicDestination.searchTracks(q2.query(), null, 10))
                    .thenReturn(new DestinationSearchResult(List.of(), null, 0));

            CandidateDiscoveryResult result = discoveryService.discoverCandidates(new CandidateDiscoveryRequest(track, options));

            assertThat(result.status()).isEqualTo(CandidateDiscoveryStatus.EMPTY);
            assertThat(result.isEmpty()).isTrue();
            assertThat(result.isSuccessful()).isFalse();
            assertThat(result.hasCandidates()).isFalse();
            assertThat(result.candidates()).isEmpty();
            assertThat(result.discoveredCandidates()).isEmpty();
            assertThat(result.executedQueries()).containsExactly(
                    SearchQueryExecution.success(q1, 0),
                    SearchQueryExecution.success(q2, 0)
            );
            assertThat(result.errorMessage()).isNull();
        }
    }

    @Nested
    @DisplayName("Case 3: Some Queries Fail While Others Produce Results (PARTIAL_FAILURE)")
    class Case3SomeQueriesFailWithResultsPreserved {

        @Test
        @DisplayName("First query succeeds and second query fails preserves candidates with PARTIAL_FAILURE")
        void firstQuerySucceedsSecondFailsPreservesCandidates() {
            NormalizedTrackMetadata track = sampleSourceTrack();
            DiscoveryOptions options = new DiscoveryOptions(10, 2, 10, null);
            SearchQuery q1 = query("Daft Punk - Get Lucky", SearchQueryStrategy.PRIMARY_TITLE_ARTIST, 1);
            SearchQuery q2 = query("Daft Punk feat Pharrell", SearchQueryStrategy.TITLE_ARTIST_FEATURED, 2);
            when(queryGenerator.generateQueries(track, options)).thenReturn(List.of(q1, q2));

            DestinationTrackCandidate c1 = candidate("vid1", "Get Lucky");
            when(musicDestination.searchTracks(q1.query(), null, 10))
                    .thenReturn(new DestinationSearchResult(List.of(c1), null, 1));
            when(musicDestination.searchTracks(q2.query(), null, 10))
                    .thenThrow(new ExternalProviderException("YouTube API quota exceeded", 403, null));

            CandidateDiscoveryResult result = discoveryService.discoverCandidates(new CandidateDiscoveryRequest(track, options));

            assertThat(result.status()).isEqualTo(CandidateDiscoveryStatus.PARTIAL_FAILURE);
            assertThat(result.isSuccessful()).isTrue();
            assertThat(result.hasCandidates()).isTrue();
            assertThat(result.candidates()).containsExactly(c1);
            assertThat(result.discoveredCandidates()).hasSize(1);
            assertThat(result.executedQueries()).containsExactly(
                    SearchQueryExecution.success(q1, 1),
                    SearchQueryExecution.failure(q2, "YouTube API quota exceeded")
            );
            assertThat(result.errorMessage()).isEqualTo("YouTube API quota exceeded");
        }

        @Test
        @DisplayName("First query fails but second query succeeds produces PARTIAL_FAILURE")
        void firstQueryFailsSecondSucceedsProducesPartialFailure() {
            NormalizedTrackMetadata track = sampleSourceTrack();
            DiscoveryOptions options = new DiscoveryOptions(10, 2, 10, null);
            SearchQuery q1 = query("Daft Punk - Get Lucky", SearchQueryStrategy.PRIMARY_TITLE_ARTIST, 1);
            SearchQuery q2 = query("Daft Punk feat Pharrell", SearchQueryStrategy.TITLE_ARTIST_FEATURED, 2);
            when(queryGenerator.generateQueries(track, options)).thenReturn(List.of(q1, q2));

            DestinationTrackCandidate c2 = candidate("vid2", "Get Lucky feat Pharrell");
            when(musicDestination.searchTracks(q1.query(), null, 10))
                    .thenThrow(new ExternalProviderException("Read timeout", 504, null));
            when(musicDestination.searchTracks(q2.query(), null, 10))
                    .thenReturn(new DestinationSearchResult(List.of(c2), null, 1));

            CandidateDiscoveryResult result = discoveryService.discoverCandidates(new CandidateDiscoveryRequest(track, options));

            assertThat(result.status()).isEqualTo(CandidateDiscoveryStatus.PARTIAL_FAILURE);
            assertThat(result.isSuccessful()).isTrue();
            assertThat(result.hasCandidates()).isTrue();
            assertThat(result.candidates()).containsExactly(c2);
            assertThat(result.executedQueries()).containsExactly(
                    SearchQueryExecution.failure(q1, "Read timeout"),
                    SearchQueryExecution.success(q2, 1)
            );
            assertThat(result.errorMessage()).isEqualTo("Read timeout");
        }
    }

    @Nested
    @DisplayName("Case 4: Some Queries Fail While Successful Queries Return No Results (FAILED)")
    class Case4SomeQueriesFailAndZeroResults {

        @Test
        @DisplayName("Query 1 returns 0 results and Query 2 fails results in FAILED with diagnostics")
        void query1EmptyQuery2FailsResultsInFailed() {
            NormalizedTrackMetadata track = sampleSourceTrack();
            DiscoveryOptions options = new DiscoveryOptions(10, 2, 10, null);
            SearchQuery q1 = query("Daft Punk - Get Lucky", SearchQueryStrategy.PRIMARY_TITLE_ARTIST, 1);
            SearchQuery q2 = query("Daft Punk feat Pharrell", SearchQueryStrategy.TITLE_ARTIST_FEATURED, 2);
            when(queryGenerator.generateQueries(track, options)).thenReturn(List.of(q1, q2));

            when(musicDestination.searchTracks(q1.query(), null, 10))
                    .thenReturn(new DestinationSearchResult(List.of(), null, 0));
            when(musicDestination.searchTracks(q2.query(), null, 10))
                    .thenThrow(new ExternalProviderException("Rate limit 429", 429, "30"));

            CandidateDiscoveryResult result = discoveryService.discoverCandidates(new CandidateDiscoveryRequest(track, options));

            assertThat(result.status()).isEqualTo(CandidateDiscoveryStatus.FAILED);
            assertThat(result.isFailed()).isTrue();
            assertThat(result.isSuccessful()).isFalse();
            assertThat(result.hasCandidates()).isFalse();
            assertThat(result.isEmpty()).isFalse();
            assertThat(result.candidates()).isEmpty();
            assertThat(result.discoveredCandidates()).isEmpty();
            assertThat(result.executedQueries()).containsExactly(
                    SearchQueryExecution.success(q1, 0),
                    SearchQueryExecution.failure(q2, "Rate limit 429")
            );
            assertThat(result.errorMessage()).isEqualTo("Rate limit 429");
        }
    }

    @Nested
    @DisplayName("Case 5: All Queries Fail Due to Provider Errors (FAILED)")
    class Case5AllQueriesFail {

        @Test
        @DisplayName("All queries fail throws ExternalProviderException results in FAILED")
        void allQueriesFailDueToProviderError() {
            NormalizedTrackMetadata track = sampleSourceTrack();
            DiscoveryOptions options = new DiscoveryOptions(10, 2, 10, null);
            SearchQuery q1 = query("Daft Punk - Get Lucky", SearchQueryStrategy.PRIMARY_TITLE_ARTIST, 1);
            SearchQuery q2 = query("Daft Punk feat Pharrell", SearchQueryStrategy.TITLE_ARTIST_FEATURED, 2);
            when(queryGenerator.generateQueries(track, options)).thenReturn(List.of(q1, q2));

            when(musicDestination.searchTracks(q1.query(), null, 10))
                    .thenThrow(new ExternalProviderException("YouTube API quota exceeded", 403, null));
            when(musicDestination.searchTracks(q2.query(), null, 10))
                    .thenThrow(new ExternalProviderException("YouTube API quota exceeded", 403, null));

            CandidateDiscoveryResult result = discoveryService.discoverCandidates(new CandidateDiscoveryRequest(track, options));

            assertThat(result.status()).isEqualTo(CandidateDiscoveryStatus.FAILED);
            assertThat(result.isFailed()).isTrue();
            assertThat(result.isSuccessful()).isFalse();
            assertThat(result.hasCandidates()).isFalse();
            assertThat(result.candidates()).isEmpty();
            assertThat(result.discoveredCandidates()).isEmpty();
            assertThat(result.executedQueries()).containsExactly(
                    SearchQueryExecution.failure(q1, "YouTube API quota exceeded"),
                    SearchQueryExecution.failure(q2, "YouTube API quota exceeded")
            );
            assertThat(result.errorMessage()).isEqualTo("YouTube API quota exceeded; YouTube API quota exceeded");
        }
    }

    @Nested
    @DisplayName("Case 6: Invalid or Empty Provider Responses")
    class Case6InvalidOrEmptyProviderResponses {

        @Test
        @DisplayName("Null DestinationSearchResult handled gracefully as 0 results")
        void nullSearchResultHandledGracefully() {
            NormalizedTrackMetadata track = sampleSourceTrack();
            DiscoveryOptions options = new DiscoveryOptions(10, 1, 10, null);
            SearchQuery q1 = query("Daft Punk - Get Lucky", SearchQueryStrategy.PRIMARY_TITLE_ARTIST, 1);
            when(queryGenerator.generateQueries(track, options)).thenReturn(List.of(q1));

            when(musicDestination.searchTracks(q1.query(), null, 10)).thenReturn(null);

            CandidateDiscoveryResult result = discoveryService.discoverCandidates(new CandidateDiscoveryRequest(track, options));

            assertThat(result.status()).isEqualTo(CandidateDiscoveryStatus.EMPTY);
            assertThat(result.executedQueries()).containsExactly(SearchQueryExecution.success(q1, 0));
        }

        @Test
        @DisplayName("Null candidates list in DestinationSearchResult handled gracefully as 0 results")
        void nullCandidatesListHandledGracefully() {
            NormalizedTrackMetadata track = sampleSourceTrack();
            DiscoveryOptions options = new DiscoveryOptions(10, 1, 10, null);
            SearchQuery q1 = query("Daft Punk - Get Lucky", SearchQueryStrategy.PRIMARY_TITLE_ARTIST, 1);
            when(queryGenerator.generateQueries(track, options)).thenReturn(List.of(q1));

            when(musicDestination.searchTracks(q1.query(), null, 10))
                    .thenReturn(new DestinationSearchResult(null, null, 0));

            CandidateDiscoveryResult result = discoveryService.discoverCandidates(new CandidateDiscoveryRequest(track, options));

            assertThat(result.status()).isEqualTo(CandidateDiscoveryStatus.EMPTY);
            assertThat(result.executedQueries()).containsExactly(SearchQueryExecution.success(q1, 0));
        }

        @Test
        @DisplayName("Malformed candidates with null or blank IDs are ignored")
        void malformedCandidatesIgnored() {
            NormalizedTrackMetadata track = sampleSourceTrack();
            DiscoveryOptions options = new DiscoveryOptions(10, 1, 10, null);
            SearchQuery q1 = query("Daft Punk - Get Lucky", SearchQueryStrategy.PRIMARY_TITLE_ARTIST, 1);
            when(queryGenerator.generateQueries(track, options)).thenReturn(List.of(q1));

            DestinationTrackCandidate valid = candidate("vid1", "Valid Video");
            DestinationTrackCandidate nullId = new DestinationTrackCandidate(null, "Title", "Channel", "Desc", "url", "2023");
            DestinationTrackCandidate blankId = new DestinationTrackCandidate("   ", "Title", "Channel", "Desc", "url", "2023");

            when(musicDestination.searchTracks(q1.query(), null, 10))
                    .thenReturn(new DestinationSearchResult(List.of(valid, nullId, blankId), null, 3));

            CandidateDiscoveryResult result = discoveryService.discoverCandidates(new CandidateDiscoveryRequest(track, options));

            assertThat(result.status()).isEqualTo(CandidateDiscoveryStatus.SUCCESS);
            assertThat(result.candidates()).containsExactly(valid);
            assertThat(result.discoveredCandidates()).containsExactly(new DiscoveredCandidate(valid, q1));
        }
    }

    @Nested
    @DisplayName("Case 7: Quota Bounding & Limits")
    class Case7QuotaBoundingAndLimits {

        @Test
        @DisplayName("Does not execute more queries than DiscoveryOptions.maxQueries")
        void strictlyRespectsMaxQueriesLimit() {
            NormalizedTrackMetadata track = sampleSourceTrack();
            // maxQueries = 2, but generator returns 4 queries
            DiscoveryOptions options = new DiscoveryOptions(20, 2, 10, null);
            SearchQuery q1 = query("Q1", SearchQueryStrategy.PRIMARY_TITLE_ARTIST, 1);
            SearchQuery q2 = query("Q2", SearchQueryStrategy.TITLE_ARTIST_FEATURED, 2);
            SearchQuery q3 = query("Q3", SearchQueryStrategy.TITLE_ARTIST_ALBUM, 3);
            SearchQuery q4 = query("Q4", SearchQueryStrategy.RAW_TITLE_AND_ARTIST, 4);

            when(queryGenerator.generateQueries(track, options)).thenReturn(List.of(q1, q2, q3, q4));

            when(musicDestination.searchTracks("Q1", null, 10))
                    .thenReturn(new DestinationSearchResult(List.of(candidate("v1", "T1")), null, 1));
            when(musicDestination.searchTracks("Q2", null, 10))
                    .thenReturn(new DestinationSearchResult(List.of(candidate("v2", "T2")), null, 1));

            CandidateDiscoveryResult result = discoveryService.discoverCandidates(new CandidateDiscoveryRequest(track, options));

            assertThat(result.executedQueries()).hasSize(2);
            assertThat(result.executedQueries()).extracting(SearchQueryExecution::query).containsExactly(q1, q2);
            verify(musicDestination, times(1)).searchTracks("Q1", null, 10);
            verify(musicDestination, times(1)).searchTracks("Q2", null, 10);
            verify(musicDestination, never()).searchTracks("Q3", null, 10);
            verify(musicDestination, never()).searchTracks("Q4", null, 10);
        }

        @Test
        @DisplayName("Early termination: skips downstream queries when maxCandidates reached")
        void earlyTerminationWhenMaxCandidatesReached() {
            NormalizedTrackMetadata track = sampleSourceTrack();
            // maxCandidates = 2, maxQueries = 3
            DiscoveryOptions options = new DiscoveryOptions(2, 3, 10, null);
            SearchQuery q1 = query("Q1", SearchQueryStrategy.PRIMARY_TITLE_ARTIST, 1);
            SearchQuery q2 = query("Q2", SearchQueryStrategy.TITLE_ARTIST_FEATURED, 2);
            SearchQuery q3 = query("Q3", SearchQueryStrategy.TITLE_ARTIST_ALBUM, 3);

            when(queryGenerator.generateQueries(track, options)).thenReturn(List.of(q1, q2, q3));

            // Q1 already yields 2 unique candidates
            DestinationTrackCandidate c1 = candidate("vid1", "Track 1");
            DestinationTrackCandidate c2 = candidate("vid2", "Track 2");
            when(musicDestination.searchTracks("Q1", null, 10))
                    .thenReturn(new DestinationSearchResult(List.of(c1, c2), null, 2));

            CandidateDiscoveryResult result = discoveryService.discoverCandidates(new CandidateDiscoveryRequest(track, options));

            assertThat(result.status()).isEqualTo(CandidateDiscoveryStatus.SUCCESS);
            assertThat(result.candidateCount()).isEqualTo(2);
            assertThat(result.executedQueries()).hasSize(1);
            assertThat(result.executedQueries().get(0).query()).isEqualTo(q1);

            // Q2 and Q3 are never called!
            verify(musicDestination, times(1)).searchTracks("Q1", null, 10);
            verify(musicDestination, never()).searchTracks("Q2", null, 10);
            verify(musicDestination, never()).searchTracks("Q3", null, 10);
        }

        @Test
        @DisplayName("Truncates candidate collection to maxCandidates even if single query returns more")
        void truncatesCandidatesToMaxCandidatesLimit() {
            NormalizedTrackMetadata track = sampleSourceTrack();
            // maxCandidates = 2, resultsPerQuery = 5
            DiscoveryOptions options = new DiscoveryOptions(2, 2, 5, null);
            SearchQuery q1 = query("Q1", SearchQueryStrategy.PRIMARY_TITLE_ARTIST, 1);
            when(queryGenerator.generateQueries(track, options)).thenReturn(List.of(q1));

            DestinationTrackCandidate c1 = candidate("v1", "T1");
            DestinationTrackCandidate c2 = candidate("v2", "T2");
            DestinationTrackCandidate c3 = candidate("v3", "T3");
            DestinationTrackCandidate c4 = candidate("v4", "T4");

            when(musicDestination.searchTracks("Q1", null, 5))
                    .thenReturn(new DestinationSearchResult(List.of(c1, c2, c3, c4), null, 4));

            CandidateDiscoveryResult result = discoveryService.discoverCandidates(new CandidateDiscoveryRequest(track, options));

            assertThat(result.candidates()).containsExactly(c1, c2);
            assertThat(result.candidateCount()).isEqualTo(2);
            assertThat(result.discoveredCandidates()).containsExactly(
                    new DiscoveredCandidate(c1, q1),
                    new DiscoveredCandidate(c2, q1)
            );
            // SearchQueryExecution records total items returned by the provider query
            assertThat(result.executedQueries().get(0).resultCount()).isEqualTo(4);
        }

        @Test
        @DisplayName("Passes DiscoveryOptions.resultsPerQuery directly to destination.searchTracks")
        void passesResultsPerQueryLimitToDestination() {
            NormalizedTrackMetadata track = sampleSourceTrack();
            DiscoveryOptions options = new DiscoveryOptions(25, 1, 35, null);
            SearchQuery q1 = query("Q1", SearchQueryStrategy.PRIMARY_TITLE_ARTIST, 1);
            when(queryGenerator.generateQueries(track, options)).thenReturn(List.of(q1));

            when(musicDestination.searchTracks("Q1", null, 35))
                    .thenReturn(new DestinationSearchResult(List.of(), null, 0));

            discoveryService.discoverCandidates(new CandidateDiscoveryRequest(track, options));

            verify(musicDestination).searchTracks("Q1", null, 35);
        }
    }

    @Nested
    @DisplayName("Case 8: Deduplication and Provenance Preservation")
    class Case8DeduplicationAndProvenance {

        @Test
        @DisplayName("Within-query duplicate IDs are ignored in DiscoveredCandidate")
        void withinQueryDuplicateIdsIgnored() {
            NormalizedTrackMetadata track = sampleSourceTrack();
            DiscoveryOptions options = new DiscoveryOptions(10, 1, 10, null);
            SearchQuery q1 = query("Q1", SearchQueryStrategy.PRIMARY_TITLE_ARTIST, 1);
            when(queryGenerator.generateQueries(track, options)).thenReturn(List.of(q1));

            DestinationTrackCandidate c1 = candidate("v1", "T1");
            DestinationTrackCandidate c1Dup = candidate("v1", "T1 Duplicate");

            when(musicDestination.searchTracks("Q1", null, 10))
                    .thenReturn(new DestinationSearchResult(List.of(c1, c1Dup), null, 2));

            CandidateDiscoveryResult result = discoveryService.discoverCandidates(new CandidateDiscoveryRequest(track, options));

            assertThat(result.candidates()).containsExactly(c1);
            assertThat(result.discoveredCandidates()).containsExactly(new DiscoveredCandidate(c1, q1));
        }

        @Test
        @DisplayName("Cross-query duplicate candidate preserves provenance in discoveredCandidates and deduplicates in candidates")
        void crossQueryDuplicatePreservesProvenance() {
            NormalizedTrackMetadata track = sampleSourceTrack();
            DiscoveryOptions options = new DiscoveryOptions(10, 2, 5, null);
            SearchQuery q1 = query("Q1", SearchQueryStrategy.PRIMARY_TITLE_ARTIST, 1);
            SearchQuery q2 = query("Q2", SearchQueryStrategy.TITLE_ARTIST_FEATURED, 2);
            when(queryGenerator.generateQueries(track, options)).thenReturn(List.of(q1, q2));

            DestinationTrackCandidate c1 = candidate("v1", "Title From Q1");
            DestinationTrackCandidate c2 = candidate("v2", "Title 2");
            DestinationTrackCandidate c1FromQ2 = candidate("v1", "Title From Q2 Duplicate");

            when(musicDestination.searchTracks("Q1", null, 5))
                    .thenReturn(new DestinationSearchResult(List.of(c1, c2), null, 2));
            when(musicDestination.searchTracks("Q2", null, 5))
                    .thenReturn(new DestinationSearchResult(List.of(c1FromQ2), null, 1));

            CandidateDiscoveryResult result = discoveryService.discoverCandidates(new CandidateDiscoveryRequest(track, options));

            assertThat(result.status()).isEqualTo(CandidateDiscoveryStatus.SUCCESS);
            // candidates contains unique c1 and c2 in encounter order
            assertThat(result.candidates()).containsExactly(c1, c2);
            // discoveredCandidates captures both q1 and q2 discoveries of v1
            assertThat(result.discoveredCandidates()).containsExactly(
                    new DiscoveredCandidate(c1, q1),
                    new DiscoveredCandidate(c2, q1),
                    new DiscoveredCandidate(c1FromQ2, q2)
            );
        }
    }

    @Nested
    @DisplayName("Case 9: Exception Handling and Diagnostics")
    class Case9ExceptionHandlingAndDiagnostics {

        @Test
        @DisplayName("ResourceNotFoundException is recorded as query failure")
        void handlesResourceNotFoundException() {
            NormalizedTrackMetadata track = sampleSourceTrack();
            DiscoveryOptions options = new DiscoveryOptions(10, 1, 10, null);
            SearchQuery q1 = query("Q1", SearchQueryStrategy.PRIMARY_TITLE_ARTIST, 1);
            when(queryGenerator.generateQueries(track, options)).thenReturn(List.of(q1));

            when(musicDestination.searchTracks("Q1", null, 10))
                    .thenThrow(new ResourceNotFoundException("YouTube search endpoint not found"));

            CandidateDiscoveryResult result = discoveryService.discoverCandidates(new CandidateDiscoveryRequest(track, options));

            assertThat(result.status()).isEqualTo(CandidateDiscoveryStatus.FAILED);
            assertThat(result.errorMessage()).isEqualTo("YouTube search endpoint not found");
            assertThat(result.executedQueries()).containsExactly(
                    SearchQueryExecution.failure(q1, "YouTube search endpoint not found")
            );
        }

        @Test
        @DisplayName("Exception with null message records class name as fallback error")
        void handlesExceptionWithNullMessage() {
            NormalizedTrackMetadata track = sampleSourceTrack();
            DiscoveryOptions options = new DiscoveryOptions(10, 1, 10, null);
            SearchQuery q1 = query("Q1", SearchQueryStrategy.PRIMARY_TITLE_ARTIST, 1);
            when(queryGenerator.generateQueries(track, options)).thenReturn(List.of(q1));

            when(musicDestination.searchTracks("Q1", null, 10))
                    .thenThrow(new RuntimeException());

            CandidateDiscoveryResult result = discoveryService.discoverCandidates(new CandidateDiscoveryRequest(track, options));

            assertThat(result.status()).isEqualTo(CandidateDiscoveryStatus.FAILED);
            assertThat(result.errorMessage()).isEqualTo("RuntimeException");
            assertThat(result.executedQueries()).containsExactly(
                    SearchQueryExecution.failure(q1, "RuntimeException")
            );
        }

        @Test
        @DisplayName("Authentication 401 and Quota 403 ExternalProviderException are properly captured in diagnostics")
        void handlesAuthAndQuotaExceptions() {
            NormalizedTrackMetadata track = sampleSourceTrack();
            DiscoveryOptions options = new DiscoveryOptions(10, 2, 10, null);
            SearchQuery q1 = query("Q1", SearchQueryStrategy.PRIMARY_TITLE_ARTIST, 1);
            SearchQuery q2 = query("Q2", SearchQueryStrategy.TITLE_ARTIST_FEATURED, 2);
            when(queryGenerator.generateQueries(track, options)).thenReturn(List.of(q1, q2));

            DestinationTrackCandidate c1 = candidate("vid1", "Track 1");
            when(musicDestination.searchTracks("Q1", null, 10))
                    .thenReturn(new DestinationSearchResult(List.of(c1), null, 1));
            when(musicDestination.searchTracks("Q2", null, 10))
                    .thenThrow(new ExternalProviderException("The request cannot be completed because you have exceeded your quota.", 403));

            CandidateDiscoveryResult result = discoveryService.discoverCandidates(new CandidateDiscoveryRequest(track, options));

            assertThat(result.status()).isEqualTo(CandidateDiscoveryStatus.PARTIAL_FAILURE);
            assertThat(result.errorMessage()).contains("exceeded your quota");
            assertThat(result.candidates()).containsExactly(c1);
            assertThat(result.executedQueries()).hasSize(2);
            assertThat(result.executedQueries().get(1).successful()).isFalse();
            assertThat(result.executedQueries().get(1).errorMessage()).contains("exceeded your quota");
        }
    }

    @Nested
    @DisplayName("Case 10: Canonical ID Normalization and Alternate Versions Preservation")
    class Case10ValidationAndFiltering {

        @Test
        @DisplayName("Whitespace-padded video IDs are canonicalized and deduplicated against trimmed IDs")
        void whitespacePaddedIdsAreCanonicalizedAndDeduplicated() {
            NormalizedTrackMetadata track = sampleSourceTrack();
            DiscoveryOptions options = new DiscoveryOptions(10, 2, 5, null);
            SearchQuery q1 = query("Q1", SearchQueryStrategy.PRIMARY_TITLE_ARTIST, 1);
            SearchQuery q2 = query("Q2", SearchQueryStrategy.TITLE_ARTIST_FEATURED, 2);
            when(queryGenerator.generateQueries(track, options)).thenReturn(List.of(q1, q2));

            DestinationTrackCandidate c1 = new DestinationTrackCandidate("  vid1  ", "Title 1", "Artist", null, null, null);
            DestinationTrackCandidate c1Exact = new DestinationTrackCandidate("vid1", "Title 1 Variant", "Artist", null, null, null);

            when(musicDestination.searchTracks("Q1", null, 5))
                    .thenReturn(new DestinationSearchResult(List.of(c1), null, 1));
            when(musicDestination.searchTracks("Q2", null, 5))
                    .thenReturn(new DestinationSearchResult(List.of(c1Exact), null, 1));

            CandidateDiscoveryResult result = discoveryService.discoverCandidates(new CandidateDiscoveryRequest(track, options));

            assertThat(result.candidates()).hasSize(1);
            assertThat(result.candidates().get(0).id()).isEqualTo("vid1");
            assertThat(result.discoveredCandidates()).hasSize(2);
        }

        @Test
        @DisplayName("Legitimate alternate versions (Live, Remix, Acoustic, Cover, Karaoke, Remaster, Radio Edit, Slowed, Sped Up) are preserved")
        void preservesLegitimateAlternateVersions() {
            NormalizedTrackMetadata track = sampleSourceTrack();
            DiscoveryOptions options = new DiscoveryOptions(10, 1, 10, null);
            SearchQuery q1 = query("Q1", SearchQueryStrategy.PRIMARY_TITLE_ARTIST, 1);
            when(queryGenerator.generateQueries(track, options)).thenReturn(List.of(q1));

            List<DestinationTrackCandidate> variants = List.of(
                    candidate("v1", "Hotel California - Live at the Forum 1976"),
                    candidate("v2", "Hotel California (Acoustic Version)"),
                    candidate("v3", "Hotel California [2013 Remaster]"),
                    candidate("v4", "Hotel California (Remix)"),
                    candidate("v5", "Hotel California - Cover"),
                    candidate("v6", "Hotel California (Karaoke Version)"),
                    candidate("v7", "Hotel California (Radio Edit)"),
                    candidate("v8", "Hotel California (Slowed + Reverb)"),
                    candidate("v9", "Hotel California (Sped Up)")
            );

            when(musicDestination.searchTracks("Q1", null, 10))
                    .thenReturn(new DestinationSearchResult(variants, null, variants.size()));

            CandidateDiscoveryResult result = discoveryService.discoverCandidates(new CandidateDiscoveryRequest(track, options));

            assertThat(result.status()).isEqualTo(CandidateDiscoveryStatus.SUCCESS);
            assertThat(result.candidates()).hasSize(9);
            assertThat(result.candidates()).extracting(DestinationTrackCandidate::id)
                    .containsExactly("v1", "v2", "v3", "v4", "v5", "v6", "v7", "v8", "v9");
        }

        @Test
        @DisplayName("Candidates with missing optional metadata (null title, channel, description, duration) are accepted")
        void acceptsCandidatesWithMissingOptionalMetadata() {
            NormalizedTrackMetadata track = sampleSourceTrack();
            DiscoveryOptions options = new DiscoveryOptions(10, 1, 10, null);
            SearchQuery q1 = query("Q1", SearchQueryStrategy.PRIMARY_TITLE_ARTIST, 1);
            when(queryGenerator.generateQueries(track, options)).thenReturn(List.of(q1));

            DestinationTrackCandidate minimal = new DestinationTrackCandidate("vMin", null, null, null, null, null, (Duration) null);

            when(musicDestination.searchTracks("Q1", null, 10))
                    .thenReturn(new DestinationSearchResult(List.of(minimal), null, 1));

            CandidateDiscoveryResult result = discoveryService.discoverCandidates(new CandidateDiscoveryRequest(track, options));

            assertThat(result.status()).isEqualTo(CandidateDiscoveryStatus.SUCCESS);
            assertThat(result.candidates()).containsExactly(minimal);
        }
    }

    @Nested
    @DisplayName("Case 11: Candidate Bounding and Continued Execution on Duplicates")
    class Case11BoundingAndContinuedExecution {

        @Test
        @DisplayName("Continued query execution occurs when duplicates in query 1 leave unique candidate count below maxCandidates")
        void continuesExecutionWhenDuplicatesLeaveCountBelowMaxCandidates() {
            NormalizedTrackMetadata track = sampleSourceTrack();
            // maxCandidates = 3
            DiscoveryOptions options = new DiscoveryOptions(3, 2, 5, null);
            SearchQuery q1 = query("Q1", SearchQueryStrategy.PRIMARY_TITLE_ARTIST, 1);
            SearchQuery q2 = query("Q2", SearchQueryStrategy.TITLE_ARTIST_FEATURED, 2);
            when(queryGenerator.generateQueries(track, options)).thenReturn(List.of(q1, q2));

            // Query 1 returns 3 items, but 2 share the same ID "v1" -> only 2 unique candidates
            DestinationTrackCandidate c1 = candidate("v1", "Track 1");
            DestinationTrackCandidate c1Dup = candidate("v1", "Track 1 Dup");
            DestinationTrackCandidate c2 = candidate("v2", "Track 2");

            when(musicDestination.searchTracks("Q1", null, 5))
                    .thenReturn(new DestinationSearchResult(List.of(c1, c1Dup, c2), null, 3));

            // Query 2 supplies the missing 3rd unique candidate
            DestinationTrackCandidate c3 = candidate("v3", "Track 3");
            when(musicDestination.searchTracks("Q2", null, 5))
                    .thenReturn(new DestinationSearchResult(List.of(c3), null, 1));

            CandidateDiscoveryResult result = discoveryService.discoverCandidates(new CandidateDiscoveryRequest(track, options));

            // Verified: Query 2 was NOT skipped because unique count was 2 < 3
            verify(musicDestination).searchTracks("Q2", null, 5);
            assertThat(result.candidateCount()).isEqualTo(3);
            assertThat(result.candidates()).containsExactly(c1, c2, c3);
        }

        @Test
        @DisplayName("Enforces minimum maxCandidates bound of 1")
        void enforcesMinMaxCandidatesBoundOf1() {
            NormalizedTrackMetadata track = sampleSourceTrack();
            DiscoveryOptions options = new DiscoveryOptions(1, 3, 5, null);
            SearchQuery q1 = query("Q1", SearchQueryStrategy.PRIMARY_TITLE_ARTIST, 1);
            SearchQuery q2 = query("Q2", SearchQueryStrategy.TITLE_ARTIST_FEATURED, 2);
            when(queryGenerator.generateQueries(track, options)).thenReturn(List.of(q1, q2));

            DestinationTrackCandidate c1 = candidate("v1", "Track 1");
            DestinationTrackCandidate c2 = candidate("v2", "Track 2");

            when(musicDestination.searchTracks("Q1", null, 5))
                    .thenReturn(new DestinationSearchResult(List.of(c1, c2), null, 2));

            CandidateDiscoveryResult result = discoveryService.discoverCandidates(new CandidateDiscoveryRequest(track, options));

            assertThat(result.candidateCount()).isEqualTo(1);
            assertThat(result.candidates()).containsExactly(c1);
            verify(musicDestination, never()).searchTracks(eq("Q2"), any(), anyInt());
        }

        @Test
        @DisplayName("Enforces maximum maxCandidates bound of 50 without artificial padding")
        void enforcesMaxMaxCandidatesBoundOf50WithoutPadding() {
            NormalizedTrackMetadata track = sampleSourceTrack();
            DiscoveryOptions options = new DiscoveryOptions(50, 1, 50, null);
            SearchQuery q1 = query("Q1", SearchQueryStrategy.PRIMARY_TITLE_ARTIST, 1);
            when(queryGenerator.generateQueries(track, options)).thenReturn(List.of(q1));

            DestinationTrackCandidate c1 = candidate("v1", "Track 1");
            when(musicDestination.searchTracks("Q1", null, 50))
                    .thenReturn(new DestinationSearchResult(List.of(c1), null, 1));

            CandidateDiscoveryResult result = discoveryService.discoverCandidates(new CandidateDiscoveryRequest(track, options));

            // Only 1 candidate returned by provider, no synthetic padding
            assertThat(result.candidateCount()).isEqualTo(1);
            assertThat(result.candidates()).containsExactly(c1);
        }
    }
}

