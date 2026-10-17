package com.spotifyyoutube.migrator.migration.review.application;

import com.spotifyyoutube.migrator.common.exception.ExternalProviderException;
import com.spotifyyoutube.migrator.common.exception.InvalidStateException;
import com.spotifyyoutube.migrator.common.exception.ResourceNotFoundException;
import com.spotifyyoutube.migrator.identity.domain.User;
import com.spotifyyoutube.migrator.matching.discovery.CandidateDiscoveryRequest;
import com.spotifyyoutube.migrator.matching.discovery.CandidateDiscoveryResult;
import com.spotifyyoutube.migrator.matching.discovery.CandidateDiscoveryService;
import com.spotifyyoutube.migrator.matching.discovery.DiscoveredCandidate;
import com.spotifyyoutube.migrator.matching.discovery.SearchQuery;
import com.spotifyyoutube.migrator.matching.discovery.SearchQueryExecution;
import com.spotifyyoutube.migrator.matching.discovery.SearchQueryStrategy;
import com.spotifyyoutube.migrator.matching.normalization.NormalizationOrchestrator;
import com.spotifyyoutube.migrator.matching.normalization.NormalizedTrackMetadata;
import com.spotifyyoutube.migrator.matching.normalization.RawTrackMetadata;
import com.spotifyyoutube.migrator.migration.application.destination.MusicDestination;
import com.spotifyyoutube.migrator.migration.application.destination.model.DestinationSearchResult;
import com.spotifyyoutube.migrator.migration.application.destination.model.DestinationTrackCandidate;
import com.spotifyyoutube.migrator.migration.application.plan.PlanTrackClassification;
import com.spotifyyoutube.migrator.migration.review.domain.ReviewDecisionSource;
import com.spotifyyoutube.migrator.migration.review.domain.ReviewSession;
import com.spotifyyoutube.migrator.migration.review.domain.ReviewSessionStatus;
import com.spotifyyoutube.migrator.migration.review.domain.ReviewTrack;
import com.spotifyyoutube.migrator.migration.review.domain.ReviewTrackStatus;
import com.spotifyyoutube.migrator.migration.review.repository.ReviewSessionRepository;
import com.spotifyyoutube.migrator.migration.review.repository.ReviewTrackRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("Phase 10C: ReviewCandidateSearchService Tests")
class ReviewCandidateSearchServiceTest {

    @Mock
    private ReviewSessionRepository reviewSessionRepository;

    @Mock
    private ReviewTrackRepository reviewTrackRepository;

    @Mock
    private NormalizationOrchestrator normalizationOrchestrator;

    @Mock
    private CandidateDiscoveryService candidateDiscoveryService;

    @Mock
    private MusicDestination musicDestination;

    @Spy
    private ReviewSearchProperties properties = new ReviewSearchProperties();

    @InjectMocks
    private DefaultReviewCandidateSearchService searchService;

    private UUID userId;
    private User testUser;
    private UUID sessionId;
    private ReviewSession testSession;
    private ReviewTrack reviewTrack0;
    private ReviewTrack reviewTrack1;

    @BeforeEach
    void setUp() {
        userId = UUID.randomUUID();
        testUser = new User("reviewer@example.com");
        testUser.setId(userId);
        testUser.setDisplayName("Test Reviewer");

        sessionId = UUID.randomUUID();
        testSession = new ReviewSession(testUser, "spotify-playlist-123", "Cool Playlist", UUID.randomUUID());
        testSession.setId(sessionId);

        reviewTrack0 = new ReviewTrack();
        reviewTrack0.setId(UUID.randomUUID());
        reviewTrack0.setSession(testSession);
        reviewTrack0.setPosition(0);
        reviewTrack0.setSourceTrackId("spotify-track-0");
        reviewTrack0.setSourceTitle("Get Lucky");
        reviewTrack0.setSourceArtist("Daft Punk");
        reviewTrack0.setOriginalClassification(PlanTrackClassification.REVIEW_REQUIRED);
        reviewTrack0.setReviewStatus(ReviewTrackStatus.AWAITING_REVIEW);
        reviewTrack0.setDecisionSource(ReviewDecisionSource.AUTOMATIC);

        reviewTrack1 = new ReviewTrack();
        reviewTrack1.setId(UUID.randomUUID());
        reviewTrack1.setSession(testSession);
        reviewTrack1.setPosition(1);
        reviewTrack1.setSourceTrackId("spotify-track-1");
        reviewTrack1.setSourceTitle("Levitating");
        reviewTrack1.setSourceArtist("Dua Lipa");
        reviewTrack1.setOriginalClassification(PlanTrackClassification.UNMATCHED);
        reviewTrack1.setReviewStatus(ReviewTrackStatus.AWAITING_REVIEW);
        reviewTrack1.setDecisionSource(ReviewDecisionSource.AUTOMATIC);

        testSession.addTrack(reviewTrack0);
        testSession.addTrack(reviewTrack1);
    }

    private DestinationTrackCandidate createCandidate(String id, String title) {
        return new DestinationTrackCandidate(
                id,
                title,
                "Artist",
                "Description",
                "https://example.com/thumb.jpg",
                "2023-01-01T00:00:00Z",
                Duration.ofSeconds(240)
        );
    }

    @Nested
    @DisplayName("1. Search Behavior Tests")
    class SearchBehaviorTests {

        @Test
        @DisplayName("Search uses correct source-track metadata for automatic query formulation")
        void searchUsesCorrectSourceTrackMetadata() {
            when(reviewSessionRepository.findById(sessionId)).thenReturn(Optional.of(testSession));

            NormalizedTrackMetadata mockNormalized = org.mockito.Mockito.mock(NormalizedTrackMetadata.class);
            when(normalizationOrchestrator.normalize(any(RawTrackMetadata.class))).thenReturn(mockNormalized);

            SearchQuery sq = new SearchQuery("daft punk - get lucky", SearchQueryStrategy.PRIMARY_TITLE_ARTIST, 1);
            DestinationTrackCandidate cand = createCandidate("yt-cand-1", "Daft Punk - Get Lucky (Official Video)");
            CandidateDiscoveryResult discoveryResult = CandidateDiscoveryResult.success(
                    mockNormalized,
                    List.of(new DiscoveredCandidate(cand, sq)),
                    List.of(SearchQueryExecution.success(sq, 1))
            );
            when(candidateDiscoveryService.discoverCandidates(any(CandidateDiscoveryRequest.class)))
                    .thenReturn(discoveryResult);

            ReviewCandidateSearchResult result = searchService.searchCandidates(sessionId, 0);

            assertThat(result).isNotNull();
            assertThat(result.sessionId()).isEqualTo(sessionId);
            assertThat(result.trackPosition()).isEqualTo(0);
            assertThat(result.sourceTrackId()).isEqualTo("spotify-track-0");
            assertThat(result.sourceTitle()).isEqualTo("Get Lucky");
            assertThat(result.sourceArtist()).isEqualTo("Daft Punk");
            assertThat(result.queryUsed()).isEqualTo("daft punk - get lucky");
            assertThat(result.isCustomQuery()).isFalse();
            assertThat(result.candidates()).hasSize(1);
            assertThat(result.candidates().get(0).id()).isEqualTo("yt-cand-1");
            assertThat(result.totalResults()).isEqualTo(1);

            ArgumentCaptor<RawTrackMetadata> rawCaptor = ArgumentCaptor.forClass(RawTrackMetadata.class);
            verify(normalizationOrchestrator).normalize(rawCaptor.capture());
            RawTrackMetadata capturedRaw = rawCaptor.getValue();
            assertThat(capturedRaw.title()).isEqualTo("Get Lucky");
            assertThat(capturedRaw.artistCredit()).isEqualTo("Daft Punk");
        }

        @Test
        @DisplayName("Explicit reviewer query handling dispatches directly to MusicDestination")
        void explicitReviewerQueryDispatchesDirectlyToDestination() {
            when(reviewSessionRepository.findById(sessionId)).thenReturn(Optional.of(testSession));

            DestinationTrackCandidate cand1 = createCandidate("yt-custom-1", "Custom Match 1");
            DestinationTrackCandidate cand2 = createCandidate("yt-custom-2", "Custom Match 2");
            when(musicDestination.searchTracks(eq("daft punk radio edit"), isNull(), eq(15)))
                    .thenReturn(new DestinationSearchResult(List.of(cand1, cand2), null, 2));

            ReviewCandidateSearchRequest request = ReviewCandidateSearchRequest.of("daft punk radio edit", 15);
            ReviewCandidateSearchResult result = searchService.searchCandidates(sessionId, 0, request);

            assertThat(result).isNotNull();
            assertThat(result.isCustomQuery()).isTrue();
            assertThat(result.queryUsed()).isEqualTo("daft punk radio edit");
            assertThat(result.candidates()).containsExactly(cand1, cand2);
            assertThat(result.totalResults()).isEqualTo(2);

            verify(musicDestination).searchTracks("daft punk radio edit", null, 15);
            verifyNoInteractions(normalizationOrchestrator);
            verifyNoInteractions(candidateDiscoveryService);
        }

        @Test
        @DisplayName("Empty provider results are handled cleanly without application error")
        void emptyProviderResultsHandledCleanly() {
            when(reviewSessionRepository.findById(sessionId)).thenReturn(Optional.of(testSession));
            when(musicDestination.searchTracks(eq("nonexistent track query"), isNull(), anyInt()))
                    .thenReturn(new DestinationSearchResult(List.of(), null, 0));

            ReviewCandidateSearchRequest request = ReviewCandidateSearchRequest.of("nonexistent track query");
            ReviewCandidateSearchResult result = searchService.searchCandidates(sessionId, 0, request);

            assertThat(result).isNotNull();
            assertThat(result.isEmpty()).isTrue();
            assertThat(result.hasCandidates()).isFalse();
            assertThat(result.candidates()).isEmpty();
            assertThat(result.totalResults()).isEqualTo(0);
        }

        @Test
        @DisplayName("Duplicate candidate IDs are deduplicated deterministically in encounter order")
        void duplicateCandidateIdsDeduplicatedDeterministically() {
            when(reviewSessionRepository.findById(sessionId)).thenReturn(Optional.of(testSession));

            DestinationTrackCandidate cand1 = createCandidate("yt-duplicate", "Duplicate Video First Encounter");
            DestinationTrackCandidate cand2 = createCandidate("  yt-duplicate  ", "Duplicate Video Second Encounter");
            DestinationTrackCandidate cand3 = createCandidate("yt-unique", "Unique Video");

            when(musicDestination.searchTracks(anyString(), isNull(), anyInt()))
                    .thenReturn(new DestinationSearchResult(List.of(cand1, cand2, cand3), null, 3));

            ReviewCandidateSearchRequest request = ReviewCandidateSearchRequest.of("some query");
            ReviewCandidateSearchResult result = searchService.searchCandidates(sessionId, 0, request);

            assertThat(result.candidates()).hasSize(2);
            assertThat(result.candidates().get(0).id()).isEqualTo("yt-duplicate");
            assertThat(result.candidates().get(0).title()).isEqualTo("Duplicate Video First Encounter");
            assertThat(result.candidates().get(1).id()).isEqualTo("yt-unique");
        }

        @Test
        @DisplayName("Malformed or incomplete provider items are handled defensively")
        void malformedProviderItemsHandledDefensively() {
            when(reviewSessionRepository.findById(sessionId)).thenReturn(Optional.of(testSession));

            DestinationTrackCandidate validCand = createCandidate("yt-valid", "Valid Title");
            DestinationTrackCandidate nullIdCand = new DestinationTrackCandidate(null, "Title", "Artist", null, null, null, (Duration) null);
            DestinationTrackCandidate blankIdCand = new DestinationTrackCandidate("   ", "Title", "Artist", null, null, null, (Duration) null);

            List<DestinationTrackCandidate> rawList = new ArrayList<>();
            rawList.add(null); // null item
            rawList.add(nullIdCand);
            rawList.add(blankIdCand);
            rawList.add(validCand);

            when(musicDestination.searchTracks(anyString(), isNull(), anyInt()))
                    .thenReturn(new DestinationSearchResult(rawList, null, 1));

            ReviewCandidateSearchRequest request = ReviewCandidateSearchRequest.of("some query");
            ReviewCandidateSearchResult result = searchService.searchCandidates(sessionId, 0, request);

            assertThat(result.candidates()).containsExactly(validCand);
        }

        @Test
        @DisplayName("Effective limit truncates candidate list deterministically")
        void effectiveLimitTruncatesCandidateList() {
            when(reviewSessionRepository.findById(sessionId)).thenReturn(Optional.of(testSession));

            List<DestinationTrackCandidate> list = List.of(
                    createCandidate("yt-1", "Video 1"),
                    createCandidate("yt-2", "Video 2"),
                    createCandidate("yt-3", "Video 3"),
                    createCandidate("yt-4", "Video 4"),
                    createCandidate("yt-5", "Video 5")
            );

            when(musicDestination.searchTracks(anyString(), isNull(), eq(3)))
                    .thenReturn(new DestinationSearchResult(list, null, 5));

            ReviewCandidateSearchRequest request = ReviewCandidateSearchRequest.of("some query", 3);
            ReviewCandidateSearchResult result = searchService.searchCandidates(sessionId, 0, request);

            assertThat(result.candidates()).hasSize(3);
            assertThat(result.candidates().stream().map(DestinationTrackCandidate::id))
                    .containsExactly("yt-1", "yt-2", "yt-3");
        }
    }

    @Nested
    @DisplayName("2. Quota and Resource Safety Tests")
    class QuotaAndSafetyTests {

        @Test
        @DisplayName("Invalid limits below 1 or above 50 are rejected with IllegalArgumentException")
        void invalidLimitsRejected() {
            assertThatThrownBy(() -> ReviewCandidateSearchRequest.of("query", 0))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("limit must be between 1 and 50");

            assertThatThrownBy(() -> ReviewCandidateSearchRequest.of("query", 51))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("limit must be between 1 and 50");
        }

        @Test
        @DisplayName("Blank explicit queries are rejected with IllegalArgumentException")
        void blankQueriesRejected() {
            assertThatThrownBy(() -> ReviewCandidateSearchRequest.of("   "))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("Search query must not be blank");

            assertThatThrownBy(() -> ReviewCandidateSearchRequest.of(""))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("Search query must not be blank");
        }

        @Test
        @DisplayName("Oversized queries exceeding 500 characters are rejected safely")
        void oversizedQueriesRejected() {
            String longQuery = "a".repeat(501);
            assertThatThrownBy(() -> ReviewCandidateSearchRequest.of(longQuery))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("Search query exceeds maximum limit of 500 characters");
        }

        @Test
        @DisplayName("Quota exhaustion (HTTP 403) propagates as ExternalProviderException, not masked as empty")
        void quotaExhaustionPropagatesAndIsNotMaskedAsEmpty() {
            when(reviewSessionRepository.findById(sessionId)).thenReturn(Optional.of(testSession));
            when(musicDestination.searchTracks(anyString(), isNull(), anyInt()))
                    .thenThrow(new ExternalProviderException("YouTube API request forbidden or quota exceeded", 403, "3600"));

            ReviewCandidateSearchRequest request = ReviewCandidateSearchRequest.of("daft punk");

            assertThatThrownBy(() -> searchService.searchCandidates(sessionId, 0, request))
                    .isInstanceOf(ExternalProviderException.class)
                    .satisfies(ex -> {
                        ExternalProviderException ep = (ExternalProviderException) ex;
                        assertThat(ep.getProviderStatusCode()).isEqualTo(403);
                        assertThat(ep.getRetryAfter()).isEqualTo("3600");
                    });
        }

        @Test
        @DisplayName("Rate limits (HTTP 429) propagate with Retry-After header intact")
        void rateLimitPropagatesWithRetryAfter() {
            when(reviewSessionRepository.findById(sessionId)).thenReturn(Optional.of(testSession));
            when(musicDestination.searchTracks(anyString(), isNull(), anyInt()))
                    .thenThrow(new ExternalProviderException("YouTube API rate limit exceeded", 429, "120"));

            ReviewCandidateSearchRequest request = ReviewCandidateSearchRequest.of("daft punk");

            assertThatThrownBy(() -> searchService.searchCandidates(sessionId, 0, request))
                    .isInstanceOf(ExternalProviderException.class)
                    .satisfies(ex -> {
                        ExternalProviderException ep = (ExternalProviderException) ex;
                        assertThat(ep.getProviderStatusCode()).isEqualTo(429);
                        assertThat(ep.getRetryAfter()).isEqualTo("120");
                    });
        }

        @Test
        @DisplayName("Sensitive credentials and tokens are redacted from exception diagnostics")
        void sensitiveTokensRedactedFromExceptions() {
            when(reviewSessionRepository.findById(sessionId)).thenReturn(Optional.of(testSession));
            when(musicDestination.searchTracks(anyString(), isNull(), anyInt()))
                    .thenThrow(new ExternalProviderException("Failed with api_key=secretKey123 and Bearer ya29.secretToken", 401));

            ReviewCandidateSearchRequest request = ReviewCandidateSearchRequest.of("daft punk");

            assertThatThrownBy(() -> searchService.searchCandidates(sessionId, 0, request))
                    .isInstanceOf(ExternalProviderException.class)
                    .hasMessageNotContaining("secretKey123")
                    .hasMessageNotContaining("ya29.secretToken")
                    .hasMessageContaining("api_key=***")
                    .hasMessageContaining("Bearer ***");
        }

        @Test
        @DisplayName("Candidate discovery failure propagates as ExternalProviderException")
        void discoveryFailurePropagates() {
            when(reviewSessionRepository.findById(sessionId)).thenReturn(Optional.of(testSession));
            NormalizedTrackMetadata mockNormalized = org.mockito.Mockito.mock(NormalizedTrackMetadata.class);
            when(normalizationOrchestrator.normalize(any(RawTrackMetadata.class))).thenReturn(mockNormalized);

            CandidateDiscoveryResult failedResult = CandidateDiscoveryResult.failure(
                    mockNormalized,
                    List.of(),
                    "YouTube API request forbidden or quota exceeded"
            );
            when(candidateDiscoveryService.discoverCandidates(any(CandidateDiscoveryRequest.class)))
                    .thenReturn(failedResult);

            assertThatThrownBy(() -> searchService.searchCandidates(sessionId, 0))
                    .isInstanceOf(ExternalProviderException.class)
                    .hasMessageContaining("quota exceeded");
        }
    }

    @Nested
    @DisplayName("3. Review State Integrity and Authorization Tests")
    class ReviewIntegrityTests {

        @Test
        @DisplayName("Search operation does NOT mutate ReviewTrack reviewStatus or decisionSource")
        void searchDoesNotMutateTrackState() {
            when(reviewSessionRepository.findById(sessionId)).thenReturn(Optional.of(testSession));
            when(musicDestination.searchTracks(anyString(), isNull(), anyInt()))
                    .thenReturn(new DestinationSearchResult(List.of(createCandidate("yt-1", "Video 1")), null, 1));

            ReviewTrackStatus initialStatus = reviewTrack0.getReviewStatus();
            ReviewDecisionSource initialSource = reviewTrack0.getDecisionSource();
            String initialCandidateId = reviewTrack0.getSelectedCandidateId();
            boolean initialConflict = reviewTrack0.hasConflict();
            boolean initialExcluded = reviewTrack0.isExcluded();

            searchService.searchCandidates(sessionId, 0, ReviewCandidateSearchRequest.of("query"));

            // Verify track state remains 100% UNCHANGED
            assertThat(reviewTrack0.getReviewStatus()).isEqualTo(initialStatus);
            assertThat(reviewTrack0.getDecisionSource()).isEqualTo(initialSource);
            assertThat(reviewTrack0.getSelectedCandidateId()).isEqualTo(initialCandidateId);
            assertThat(reviewTrack0.hasConflict()).isEqualTo(initialConflict);
            assertThat(reviewTrack0.isExcluded()).isEqualTo(initialExcluded);

            // Verify no repository save calls were made
            verify(reviewSessionRepository, never()).save(any());
            verify(reviewTrackRepository, never()).save(any());
            verify(reviewTrackRepository, never()).saveAll(any());
        }

        @Test
        @DisplayName("Search operation performs ZERO MusicDestination playlist mutations")
        void searchPerformsZeroPlaylistMutations() {
            when(reviewSessionRepository.findById(sessionId)).thenReturn(Optional.of(testSession));
            when(musicDestination.searchTracks(anyString(), isNull(), anyInt()))
                    .thenReturn(new DestinationSearchResult(List.of(createCandidate("yt-1", "Video 1")), null, 1));

            searchService.searchCandidates(sessionId, 0, ReviewCandidateSearchRequest.of("query"));

            verify(musicDestination, never()).createPlaylist(any(), any());
            verify(musicDestination, never()).addTrackToPlaylist(any(), any(), any());
        }

        @Test
        @DisplayName("Searching on completed session fails fast with InvalidStateException")
        void searchOnCompletedSessionFails() {
            testSession.setStatus(ReviewSessionStatus.COMPLETED);
            when(reviewSessionRepository.findById(sessionId)).thenReturn(Optional.of(testSession));

            assertThatThrownBy(() -> searchService.searchCandidates(sessionId, 0))
                    .isInstanceOf(InvalidStateException.class)
                    .hasMessageContaining("Cannot search candidates: review session is in status COMPLETED");
        }

        @Test
        @DisplayName("Searching on cancelled session fails fast with InvalidStateException")
        void searchOnCancelledSessionFails() {
            testSession.setStatus(ReviewSessionStatus.CANCELLED);
            when(reviewSessionRepository.findById(sessionId)).thenReturn(Optional.of(testSession));

            assertThatThrownBy(() -> searchService.searchCandidates(sessionId, 0))
                    .isInstanceOf(InvalidStateException.class)
                    .hasMessageContaining("Cannot search candidates: review session is in status CANCELLED");
        }

        @Test
        @DisplayName("Missing session throws ResourceNotFoundException")
        void missingSessionThrowsNotFound() {
            UUID unknownId = UUID.randomUUID();
            when(reviewSessionRepository.findById(unknownId)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> searchService.searchCandidates(unknownId, 0))
                    .isInstanceOf(ResourceNotFoundException.class)
                    .hasMessageContaining("Review session not found with id: " + unknownId);
        }

        @Test
        @DisplayName("Mismatched user ID throws ResourceNotFoundException (anti-impersonation)")
        void mismatchedUserIdThrowsNotFound() {
            UUID wrongUser = UUID.randomUUID();
            when(reviewSessionRepository.findById(sessionId)).thenReturn(Optional.of(testSession));

            assertThatThrownBy(() -> searchService.searchCandidates(sessionId, 0, ReviewCandidateSearchRequest.defaultSearch(), wrongUser))
                    .isInstanceOf(ResourceNotFoundException.class)
                    .hasMessageContaining("Review session not found with id: " + sessionId + " for user: " + wrongUser);
        }

        @Test
        @DisplayName("Missing track position in session throws ResourceNotFoundException")
        void missingTrackPositionThrowsNotFound() {
            when(reviewSessionRepository.findById(sessionId)).thenReturn(Optional.of(testSession));
            when(reviewTrackRepository.findBySessionIdAndPosition(sessionId, 99)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> searchService.searchCandidates(sessionId, 99))
                    .isInstanceOf(ResourceNotFoundException.class)
                    .hasMessageContaining("Track not found at position 99 in review session " + sessionId);
        }

        @Test
        @DisplayName("Negative track position throws IllegalArgumentException")
        void negativeTrackPositionThrowsIllegalArgument() {
            assertThatThrownBy(() -> searchService.searchCandidates(sessionId, -1))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("position must not be negative");
        }

        @Test
        @DisplayName("Null session ID throws NullPointerException")
        void nullSessionIdThrowsNPE() {
            assertThatThrownBy(() -> searchService.searchCandidates(null, 0))
                    .isInstanceOf(NullPointerException.class)
                    .hasMessageContaining("sessionId must not be null");
        }
    }
}
