package com.spotifyyoutube.migrator.matching.engine;

import com.spotifyyoutube.migrator.matching.discovery.CandidateDiscoveryResult;
import com.spotifyyoutube.migrator.matching.normalization.NormalizedArtistCredit;
import com.spotifyyoutube.migrator.matching.normalization.NormalizedTrackMetadata;
import com.spotifyyoutube.migrator.matching.normalization.RawTrackMetadata;
import com.spotifyyoutube.migrator.matching.normalization.TokenizedTitle;
import com.spotifyyoutube.migrator.migration.application.destination.model.DestinationTrackCandidate;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("TrackMatchRequest Contract Tests")
class TrackMatchRequestTest {

    private NormalizedTrackMetadata sampleSourceTrack() {
        return new NormalizedTrackMetadata(
                RawTrackMetadata.of("Get Lucky", "Daft Punk", "Random Access Memories", 248000L),
                "get lucky",
                new NormalizedArtistCredit("daft punk", List.of()),
                List.of("daft punk"),
                List.of(),
                "random access memories",
                Duration.ofSeconds(248),
                new TokenizedTitle("Get Lucky", "get lucky", false, Set.of(), null, null, Set.of(), List.of(), List.of(), List.of())
        );
    }

    private DestinationTrackCandidate sampleCandidate(String id) {
        return new DestinationTrackCandidate(
                id,
                "Daft Punk - Get Lucky",
                "Daft Punk",
                "Description",
                "https://thumb.jpg",
                "2013-04-19",
                Duration.ofSeconds(248)
        );
    }

    @Nested
    @DisplayName("Validation and Required Fields")
    class ValidationAndRequiredFields {

        @Test
        @DisplayName("should reject null NormalizedTrackMetadata with NullPointerException")
        void shouldRejectNullSourceTrack() {
            assertThatThrownBy(() -> new TrackMatchRequest(null, List.of()))
                    .isInstanceOf(NullPointerException.class)
                    .hasMessageContaining("NormalizedTrackMetadata must not be null");
        }

        @Test
        @DisplayName("should reject null candidates list with NullPointerException")
        void shouldRejectNullCandidates() {
            assertThatThrownBy(() -> new TrackMatchRequest(sampleSourceTrack(), null))
                    .isInstanceOf(NullPointerException.class)
                    .hasMessageContaining("Candidates list must not be null");
        }

        @Test
        @DisplayName("should trim sourceTrackId if non-null")
        void shouldTrimSourceTrackId() {
            TrackMatchRequest request = new TrackMatchRequest(
                    "  spotify:track:4cOdK2wGLETKBW3PvgPWqT  ",
                    sampleSourceTrack(),
                    List.of(sampleCandidate("cand-1"))
            );

            assertThat(request.sourceTrackId()).isEqualTo("spotify:track:4cOdK2wGLETKBW3PvgPWqT");
            assertThat(request.hasSourceTrackId()).isTrue();
            assertThat(request.getSourceTrackId()).contains("spotify:track:4cOdK2wGLETKBW3PvgPWqT");
        }

        @Test
        @DisplayName("should preserve null sourceTrackId when unprovided")
        void shouldPreserveNullSourceTrackId() {
            TrackMatchRequest request = new TrackMatchRequest(sampleSourceTrack(), List.of());

            assertThat(request.sourceTrackId()).isNull();
            assertThat(request.hasSourceTrackId()).isFalse();
            assertThat(request.getSourceTrackId()).isEmpty();
        }
    }

    @Nested
    @DisplayName("Empty Candidate Policy")
    class EmptyCandidatePolicy {

        @Test
        @DisplayName("empty candidate collection is explicitly valid and preserves empty status")
        void emptyCandidateCollectionIsValid() {
            TrackMatchRequest request = new TrackMatchRequest(sampleSourceTrack(), List.of());

            assertThat(request.hasCandidates()).isFalse();
            assertThat(request.candidateCount()).isEqualTo(0);
            assertThat(request.candidates()).isEmpty();
        }

        @Test
        @DisplayName("non-empty candidate collection accurately reports counts")
        void nonEmptyCandidateCollectionReportsCounts() {
            TrackMatchRequest request = new TrackMatchRequest(
                    "sp-123",
                    sampleSourceTrack(),
                    List.of(sampleCandidate("cand-1"), sampleCandidate("cand-2"))
            );

            assertThat(request.hasCandidates()).isTrue();
            assertThat(request.candidateCount()).isEqualTo(2);
            assertThat(request.candidates()).hasSize(2);
        }
    }

    @Nested
    @DisplayName("Factory Methods from Discovery Result")
    class FactoryMethodsFromDiscoveryResult {

        @Test
        @DisplayName("fromDiscoveryResult should extract sourceTrack and candidates correctly")
        void fromDiscoveryResultShouldExtractFields() {
            CandidateDiscoveryResult discoveryResult = CandidateDiscoveryResult.empty(
                    sampleSourceTrack(),
                    List.of()
            );

            TrackMatchRequest request = TrackMatchRequest.fromDiscoveryResult("track-abc", discoveryResult);

            assertThat(request.sourceTrackId()).isEqualTo("track-abc");
            assertThat(request.sourceTrack()).isEqualTo(discoveryResult.sourceTrack());
            assertThat(request.candidates()).isEqualTo(discoveryResult.candidates());
            assertThat(request.hasCandidates()).isFalse();
        }

        @Test
        @DisplayName("fromDiscoveryResult without sourceTrackId defaults identifier to null")
        void fromDiscoveryResultWithoutIdDefaultsToNull() {
            CandidateDiscoveryResult discoveryResult = CandidateDiscoveryResult.empty(
                    sampleSourceTrack(),
                    List.of()
            );

            TrackMatchRequest request = TrackMatchRequest.fromDiscoveryResult(discoveryResult);

            assertThat(request.sourceTrackId()).isNull();
            assertThat(request.sourceTrack()).isEqualTo(discoveryResult.sourceTrack());
        }

        @Test
        @DisplayName("fromDiscoveryResult rejects null discovery result")
        void fromDiscoveryResultRejectsNull() {
            assertThatThrownBy(() -> TrackMatchRequest.fromDiscoveryResult(null))
                    .isInstanceOf(NullPointerException.class)
                    .hasMessageContaining("CandidateDiscoveryResult must not be null");
        }
    }

    @Nested
    @DisplayName("Immutability")
    class Immutability {

        @Test
        @DisplayName("candidates collection should be unmodifiable and defensively copied")
        void candidatesShouldBeUnmodifiable() {
            List<DestinationTrackCandidate> mutableList = new ArrayList<>();
            mutableList.add(sampleCandidate("cand-1"));

            TrackMatchRequest request = new TrackMatchRequest(sampleSourceTrack(), mutableList);

            // Mutating original list has no impact on request
            mutableList.add(sampleCandidate("cand-2"));
            assertThat(request.candidates()).hasSize(1);

            // Mutating through accessor throws exception
            assertThatThrownBy(() -> request.candidates().add(sampleCandidate("cand-3")))
                    .isInstanceOf(UnsupportedOperationException.class);
        }
    }
}
