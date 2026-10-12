package com.spotifyyoutube.migrator.matching.discovery;

import com.spotifyyoutube.migrator.common.exception.ExternalProviderException;
import com.spotifyyoutube.migrator.matching.normalization.NormalizedArtistCredit;
import com.spotifyyoutube.migrator.matching.normalization.NormalizedTrackMetadata;
import com.spotifyyoutube.migrator.matching.normalization.RawTrackMetadata;
import com.spotifyyoutube.migrator.matching.normalization.TokenizedTitle;
import com.spotifyyoutube.migrator.migration.application.destination.MusicDestination;
import com.spotifyyoutube.migrator.migration.application.destination.model.DestinationSearchResult;
import com.spotifyyoutube.migrator.migration.application.destination.model.DestinationTrackCandidate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Duration;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * End-to-end integration tests for the Phase 6 candidate discovery pipeline:
 * NormalizedTrackMetadata -> SearchQueryGenerator -> MusicDestination -> DefaultCandidateDiscoveryService -> CandidateDiscoveryResult.
 *
 * Verifies real query generation, bounded multi-query orchestration, deduplication,
 * provenance retention, quota preservation, and partial failure recovery.
 */
@ExtendWith(MockitoExtension.class)
class CandidateDiscoveryIntegrationTest {

    private SearchQueryGenerator realQueryGenerator;

    @Mock
    private MusicDestination musicDestination;

    private DefaultCandidateDiscoveryService discoveryService;

    @BeforeEach
    void setUp() {
        realQueryGenerator = new DefaultSearchQueryGenerator();
        discoveryService = new DefaultCandidateDiscoveryService(realQueryGenerator, musicDestination);
    }

    private NormalizedTrackMetadata sampleDaftPunkTrack() {
        return new NormalizedTrackMetadata(
                RawTrackMetadata.of("Get Lucky (feat. Pharrell Williams)", "Daft Punk", "Random Access Memories", 248000L),
                "Get Lucky",
                new NormalizedArtistCredit("Daft Punk", List.of("Pharrell Williams")),
                List.of("Daft Punk", "Pharrell Williams"),
                List.of("Pharrell Williams"),
                "Random Access Memories",
                Duration.ofMillis(248000),
                new TokenizedTitle("Get Lucky", "Get Lucky", false, Set.of(), null, null, Set.of(), List.of(), List.of(), List.of())
        );
    }

    private DestinationTrackCandidate candidate(String id, String title, String channel) {
        return new DestinationTrackCandidate(
                id,
                title,
                channel,
                "Description for " + title,
                "https://i.ytimg.com/vi/" + id + "/default.jpg",
                "2013-04-19T00:00:00Z"
        );
    }

    @Test
    @DisplayName("Full pipeline: generates real queries, executes search, deduplicates, and preserves provenance")
    void fullPipeline_endToEndExecutionWithProvenance() {
        NormalizedTrackMetadata track = sampleDaftPunkTrack();
        DiscoveryOptions options = new DiscoveryOptions(3, 3, 5, null);
        CandidateDiscoveryRequest request = new CandidateDiscoveryRequest(track, options);

        // Real generator produces:
        // Q1: PRIMARY_TITLE_ARTIST: "Daft Punk - Get Lucky"
        // Q2: TITLE_ARTIST_FEATURED: "Daft Punk feat. Pharrell Williams - Get Lucky"
        // Q3: TITLE_ARTIST_ALBUM: "Daft Punk - Get Lucky - Random Access Memories"

        DestinationTrackCandidate c1 = candidate("vid1", "Daft Punk - Get Lucky (Official Audio)", "Daft Punk");
        DestinationTrackCandidate c2 = candidate("vid2", "Daft Punk - Get Lucky (Official Video)", "Daft Punk Vevo");
        DestinationTrackCandidate c3 = candidate("vid3", "Daft Punk ft Pharrell - Get Lucky", "Music Channel");
        // Cross-query duplicate: c1 returned again in Q2
        DestinationTrackCandidate c1Dup = candidate("vid1", "Daft Punk - Get Lucky (Audio Duplicate)", "Daft Punk");

        when(musicDestination.searchTracks("Daft Punk - get lucky", null, 5))
                .thenReturn(new DestinationSearchResult(List.of(c1, c2), null, 2));
        when(musicDestination.searchTracks("Daft Punk feat. Pharrell Williams - get lucky", null, 5))
                .thenReturn(new DestinationSearchResult(List.of(c1Dup, c3), null, 2));

        CandidateDiscoveryResult result = discoveryService.discoverCandidates(request);

        assertThat(result.status()).isEqualTo(CandidateDiscoveryStatus.SUCCESS);
        assertThat(result.isSuccessful()).isTrue();
        assertThat(result.candidateCount()).isEqualTo(3);

        // Deduplication checked: unique IDs in encounter order
        assertThat(result.candidates()).containsExactly(c1, c2, c3);

        // Provenance preserved: vid1 is present for both Q1 and Q2
        assertThat(result.discoveredCandidates()).hasSize(4);
        assertThat(result.discoveredCandidates().get(0).candidate()).isEqualTo(c1);
        assertThat(result.discoveredCandidates().get(0).discoveredBy().strategy()).isEqualTo(SearchQueryStrategy.PRIMARY_TITLE_ARTIST);
        assertThat(result.discoveredCandidates().get(2).candidate().id()).isEqualTo("vid1");
        assertThat(result.discoveredCandidates().get(2).discoveredBy().strategy()).isEqualTo(SearchQueryStrategy.TITLE_ARTIST_FEATURED);

        // Third query should not be called if not needed or executed per bounds
        assertThat(result.executedQueries()).hasSize(2);
        verify(musicDestination, times(1)).searchTracks("Daft Punk - get lucky", null, 5);
        verify(musicDestination, times(1)).searchTracks("Daft Punk feat. Pharrell Williams - get lucky", null, 5);
    }

    @Test
    @DisplayName("Full pipeline quota bound: early termination occurs when maxCandidates is reached on first query")
    void fullPipeline_earlyTerminationPreservesQuota() {
        NormalizedTrackMetadata track = sampleDaftPunkTrack();
        DiscoveryOptions options = new DiscoveryOptions(2, 3, 5, null);
        CandidateDiscoveryRequest request = new CandidateDiscoveryRequest(track, options);

        DestinationTrackCandidate c1 = candidate("vid1", "Track 1", "Artist");
        DestinationTrackCandidate c2 = candidate("vid2", "Track 2", "Artist");

        when(musicDestination.searchTracks("Daft Punk - get lucky", null, 5))
                .thenReturn(new DestinationSearchResult(List.of(c1, c2), null, 2));

        CandidateDiscoveryResult result = discoveryService.discoverCandidates(request);

        assertThat(result.status()).isEqualTo(CandidateDiscoveryStatus.SUCCESS);
        assertThat(result.candidateCount()).isEqualTo(2);
        assertThat(result.candidates()).containsExactly(c1, c2);

        // Secondary queries must never be dispatched to save YouTube quota
        verify(musicDestination, times(1)).searchTracks(any(), any(), anyInt());
        verify(musicDestination, never()).searchTracks(eq("Daft Punk feat. Pharrell Williams - get lucky"), any(), anyInt());
        verify(musicDestination, never()).searchTracks(eq("Daft Punk - get lucky - random access memories"), any(), anyInt());
    }

    @Test
    @DisplayName("Full pipeline: continued query execution when duplicates leave unique count below maxCandidates")
    void fullPipeline_continuedExecutionOnDuplicates() {
        NormalizedTrackMetadata track = sampleDaftPunkTrack();
        DiscoveryOptions options = new DiscoveryOptions(3, 3, 5, null);
        CandidateDiscoveryRequest request = new CandidateDiscoveryRequest(track, options);

        DestinationTrackCandidate c1 = candidate("v1", "Track 1", "Artist");
        DestinationTrackCandidate c1Duplicate = candidate("v1", "Track 1 Duplicate", "Artist");

        when(musicDestination.searchTracks("Daft Punk - get lucky", null, 5))
                .thenReturn(new DestinationSearchResult(List.of(c1, c1Duplicate), null, 2));

        DestinationTrackCandidate c2 = candidate("v2", "Track 2", "Artist");
        DestinationTrackCandidate c3 = candidate("v3", "Track 3", "Artist");

        when(musicDestination.searchTracks("Daft Punk feat. Pharrell Williams - get lucky", null, 5))
                .thenReturn(new DestinationSearchResult(List.of(c2, c3), null, 2));

        CandidateDiscoveryResult result = discoveryService.discoverCandidates(request);

        // Verified: Q2 was dispatched because Q1 only yielded 1 unique candidate (v1), below maxCandidates (3)
        assertThat(result.candidateCount()).isEqualTo(3);
        assertThat(result.candidates()).containsExactly(c1, c2, c3);
        verify(musicDestination, times(1)).searchTracks("Daft Punk - get lucky", null, 5);
        verify(musicDestination, times(1)).searchTracks("Daft Punk feat. Pharrell Williams - get lucky", null, 5);
        // Q3 was not needed because Q2 completed the 3 unique candidates
        verify(musicDestination, never()).searchTracks(eq("Daft Punk - get lucky - random access memories"), any(), anyInt());
    }

    @Test
    @DisplayName("Full pipeline: preserves legitimate alternate versions without filtering")
    void fullPipeline_preservesLegitimateAlternateVersions() {
        NormalizedTrackMetadata track = sampleDaftPunkTrack();
        DiscoveryOptions options = new DiscoveryOptions(10, 1, 10, null);
        CandidateDiscoveryRequest request = new CandidateDiscoveryRequest(track, options);

        List<DestinationTrackCandidate> variants = List.of(
                candidate("vLive", "Get Lucky - Live at Grammys 2014", "Grammys"),
                candidate("vRemix", "Get Lucky (Daft Punk Remix)", "Daft Punk"),
                candidate("vAcoustic", "Get Lucky (Acoustic Cover)", "Acoustic Sessions"),
                candidate("vRadio", "Get Lucky (Radio Edit)", "Columbia"),
                candidate("vRemaster", "Get Lucky (2021 Remaster)", "Columbia")
        );

        when(musicDestination.searchTracks("Daft Punk - get lucky", null, 10))
                .thenReturn(new DestinationSearchResult(variants, null, variants.size()));

        CandidateDiscoveryResult result = discoveryService.discoverCandidates(request);

        assertThat(result.status()).isEqualTo(CandidateDiscoveryStatus.SUCCESS);
        assertThat(result.candidateCount()).isEqualTo(5);
        assertThat(result.candidates()).extracting(DestinationTrackCandidate::id)
                .containsExactly("vLive", "vRemix", "vAcoustic", "vRadio", "vRemaster");
    }

    @Test
    @DisplayName("Full pipeline: partial failure occurs when secondary query fails after primary succeeded")
    void fullPipeline_partialFailurePreservesPrimaryCandidates() {
        NormalizedTrackMetadata track = sampleDaftPunkTrack();
        DiscoveryOptions options = new DiscoveryOptions(5, 2, 5, null);
        CandidateDiscoveryRequest request = new CandidateDiscoveryRequest(track, options);

        DestinationTrackCandidate c1 = candidate("vid1", "Daft Punk - Get Lucky", "Artist");
        when(musicDestination.searchTracks("Daft Punk - get lucky", null, 5))
                .thenReturn(new DestinationSearchResult(List.of(c1), null, 1));

        when(musicDestination.searchTracks("Daft Punk feat. Pharrell Williams - get lucky", null, 5))
                .thenThrow(new ExternalProviderException("Quota exceeded for quota metric 'Queries'", 403));

        CandidateDiscoveryResult result = discoveryService.discoverCandidates(request);

        assertThat(result.status()).isEqualTo(CandidateDiscoveryStatus.PARTIAL_FAILURE);
        assertThat(result.isSuccessful()).isTrue();
        assertThat(result.candidateCount()).isEqualTo(1);
        assertThat(result.candidates()).containsExactly(c1);
        assertThat(result.errorMessage()).contains("Quota exceeded");
        assertThat(result.executedQueries()).hasSize(2);
        assertThat(result.executedQueries().get(0).successful()).isTrue();
        assertThat(result.executedQueries().get(1).successful()).isFalse();
    }

    @Test
    @DisplayName("Full pipeline: total provider failure yields FAILED status and records error")
    void fullPipeline_totalProviderFailure() {
        NormalizedTrackMetadata track = sampleDaftPunkTrack();
        DiscoveryOptions options = new DiscoveryOptions(5, 2, 5, null);
        CandidateDiscoveryRequest request = new CandidateDiscoveryRequest(track, options);

        when(musicDestination.searchTracks(any(), any(), anyInt()))
                .thenThrow(new ExternalProviderException("Backend service unavailable", 503));

        CandidateDiscoveryResult result = discoveryService.discoverCandidates(request);

        assertThat(result.status()).isEqualTo(CandidateDiscoveryStatus.FAILED);
        assertThat(result.isFailed()).isTrue();
        assertThat(result.candidateCount()).isZero();
        assertThat(result.candidates()).isEmpty();
        assertThat(result.errorMessage()).contains("Backend service unavailable");
    }

    @Test
    @DisplayName("Full pipeline: empty search results yield EMPTY status without errors")
    void fullPipeline_emptyResultsYieldEmptyStatus() {
        NormalizedTrackMetadata track = sampleDaftPunkTrack();
        DiscoveryOptions options = new DiscoveryOptions(5, 1, 5, null);
        CandidateDiscoveryRequest request = new CandidateDiscoveryRequest(track, options);

        when(musicDestination.searchTracks("Daft Punk - get lucky", null, 5))
                .thenReturn(new DestinationSearchResult(List.of(), null, 0));

        CandidateDiscoveryResult result = discoveryService.discoverCandidates(request);

        assertThat(result.status()).isEqualTo(CandidateDiscoveryStatus.EMPTY);
        assertThat(result.isEmpty()).isTrue();
        assertThat(result.candidateCount()).isZero();
        assertThat(result.errorMessage()).isNull();
    }

    @Test
    @DisplayName("Full pipeline: whitespace-padded IDs and blank IDs handled safely")
    void fullPipeline_whitespaceAndBlankIdsHandledSafely() {
        NormalizedTrackMetadata track = sampleDaftPunkTrack();
        DiscoveryOptions options = new DiscoveryOptions(5, 1, 5, null);
        CandidateDiscoveryRequest request = new CandidateDiscoveryRequest(track, options);

        DestinationTrackCandidate padded = new DestinationTrackCandidate("   vid123   ", "Title", "Artist", null, null, null);
        DestinationTrackCandidate blank = new DestinationTrackCandidate("   ", "Blank", "Artist", null, null, null);
        DestinationTrackCandidate nullId = new DestinationTrackCandidate(null, "Null", "Artist", null, null, null);

        when(musicDestination.searchTracks("Daft Punk - get lucky", null, 5))
                .thenReturn(new DestinationSearchResult(List.of(padded, blank, nullId), null, 3));

        CandidateDiscoveryResult result = discoveryService.discoverCandidates(request);

        assertThat(result.status()).isEqualTo(CandidateDiscoveryStatus.SUCCESS);
        assertThat(result.candidateCount()).isEqualTo(1);
        assertThat(result.candidates().get(0).id()).isEqualTo("vid123");
    }

    @Test
    @DisplayName("Full pipeline: failed query followed by successful query preserves candidates with PARTIAL_FAILURE")
    void fullPipeline_failedQueryFollowedBySuccessfulQuery() {
        NormalizedTrackMetadata track = sampleDaftPunkTrack();
        DiscoveryOptions options = new DiscoveryOptions(5, 2, 5, null);
        CandidateDiscoveryRequest request = new CandidateDiscoveryRequest(track, options);

        when(musicDestination.searchTracks("Daft Punk - get lucky", null, 5))
                .thenThrow(new ExternalProviderException("Transient network timeout", 500));

        DestinationTrackCandidate c2 = candidate("vid2", "Daft Punk feat. Pharrell - Get Lucky", "Artist");
        when(musicDestination.searchTracks("Daft Punk feat. Pharrell Williams - get lucky", null, 5))
                .thenReturn(new DestinationSearchResult(List.of(c2), null, 1));

        CandidateDiscoveryResult result = discoveryService.discoverCandidates(request);

        assertThat(result.status()).isEqualTo(CandidateDiscoveryStatus.PARTIAL_FAILURE);
        assertThat(result.isSuccessful()).isTrue();
        assertThat(result.candidateCount()).isEqualTo(1);
        assertThat(result.candidates()).containsExactly(c2);
        assertThat(result.errorMessage()).contains("Transient network timeout");
        assertThat(result.executedQueries()).hasSize(2);
        assertThat(result.executedQueries().get(0).successful()).isFalse();
        assertThat(result.executedQueries().get(1).successful()).isTrue();
    }

    @Test
    @DisplayName("Full pipeline: empty first query does not break downstream queries and yields SUCCESS")
    void fullPipeline_emptyFirstQueryFollowedBySuccessfulSecondQuery() {
        NormalizedTrackMetadata track = sampleDaftPunkTrack();
        DiscoveryOptions options = new DiscoveryOptions(5, 2, 5, null);
        CandidateDiscoveryRequest request = new CandidateDiscoveryRequest(track, options);

        when(musicDestination.searchTracks("Daft Punk - get lucky", null, 5))
                .thenReturn(new DestinationSearchResult(List.of(), null, 0));

        DestinationTrackCandidate c2 = candidate("vid2", "Daft Punk feat. Pharrell - Get Lucky", "Artist");
        when(musicDestination.searchTracks("Daft Punk feat. Pharrell Williams - get lucky", null, 5))
                .thenReturn(new DestinationSearchResult(List.of(c2), null, 1));

        CandidateDiscoveryResult result = discoveryService.discoverCandidates(request);

        assertThat(result.status()).isEqualTo(CandidateDiscoveryStatus.SUCCESS);
        assertThat(result.isSuccessful()).isTrue();
        assertThat(result.candidateCount()).isEqualTo(1);
        assertThat(result.candidates()).containsExactly(c2);
        assertThat(result.executedQueries()).hasSize(2);
        assertThat(result.executedQueries().get(0).successful()).isTrue();
        assertThat(result.executedQueries().get(0).resultCount()).isZero();
        assertThat(result.executedQueries().get(1).successful()).isTrue();
        assertThat(result.executedQueries().get(1).resultCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("Full pipeline: candidates with missing duration and null optional fields are preserved")
    void fullPipeline_candidatesWithMissingDurationAndNullOptionalFields() {
        NormalizedTrackMetadata track = sampleDaftPunkTrack();
        DiscoveryOptions options = new DiscoveryOptions(5, 1, 5, null);
        CandidateDiscoveryRequest request = new CandidateDiscoveryRequest(track, options);

        DestinationTrackCandidate minimal = new DestinationTrackCandidate(
                "vidMinimal", null, null, null, null, null, (Duration) null);

        when(musicDestination.searchTracks("Daft Punk - get lucky", null, 5))
                .thenReturn(new DestinationSearchResult(List.of(minimal), null, 1));

        CandidateDiscoveryResult result = discoveryService.discoverCandidates(request);

        assertThat(result.status()).isEqualTo(CandidateDiscoveryStatus.SUCCESS);
        assertThat(result.candidateCount()).isEqualTo(1);
        DestinationTrackCandidate discovered = result.candidates().get(0);
        assertThat(discovered.id()).isEqualTo("vidMinimal");
        assertThat(discovered.title()).isNull();
        assertThat(discovered.duration()).isNull();
    }

    @Test
    @DisplayName("Full pipeline: sensitive credentials and tokens in provider errors are masked")
    void fullPipeline_sanitizesSensitiveTokensInErrorMessages() {
        NormalizedTrackMetadata track = sampleDaftPunkTrack();
        DiscoveryOptions options = new DiscoveryOptions(5, 1, 5, null);
        CandidateDiscoveryRequest request = new CandidateDiscoveryRequest(track, options);

        when(musicDestination.searchTracks("Daft Punk - get lucky", null, 5))
                .thenThrow(new ExternalProviderException("Request to https://api.youtube.com?key=AIzaSySecret123&part=snippet Bearer ya29.secretToken456 failed", 403));

        CandidateDiscoveryResult result = discoveryService.discoverCandidates(request);

        assertThat(result.status()).isEqualTo(CandidateDiscoveryStatus.FAILED);
        assertThat(result.errorMessage()).doesNotContain("AIzaSySecret123");
        assertThat(result.errorMessage()).doesNotContain("ya29.secretToken456");
        assertThat(result.errorMessage()).contains("key=***");
        assertThat(result.errorMessage()).contains("Bearer ***");
        assertThat(result.executedQueries().get(0).errorMessage()).doesNotContain("AIzaSySecret123");
    }
}
