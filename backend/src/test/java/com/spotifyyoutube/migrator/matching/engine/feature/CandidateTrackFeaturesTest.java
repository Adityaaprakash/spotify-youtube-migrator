package com.spotifyyoutube.migrator.matching.engine.feature;

import com.spotifyyoutube.migrator.migration.application.destination.model.DestinationTrackCandidate;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("CandidateTrackFeatures Tests")
class CandidateTrackFeaturesTest {

    private DestinationTrackCandidate candidate(String id, String durationIso) {
        return new DestinationTrackCandidate(
                id,
                "Daft Punk - Get Lucky (Official Video)",
                "Daft Punk",
                "Official music video",
                "https://thumb.jpg",
                "2013-04-19",
                durationIso
        );
    }

    @Nested
    @DisplayName("Validation and Required Fields")
    class ValidationAndRequiredFields {

        @Test
        @DisplayName("should reject null candidateId with NullPointerException")
        void shouldRejectNullCandidateId() {
            DestinationTrackCandidate c = candidate("cand-1", "PT4M8S");
            assertThatThrownBy(() -> new CandidateTrackFeatures(
                    null, c.title(), "title", "title", c.channelOrArtist(), "artist",
                    c.duration(), 248000L, true, true, c.description(), c.publishedAt(),
                    CandidateVersionEvidence.empty(), OfficialSignalEvidence.none(), c
            )).isInstanceOf(NullPointerException.class)
                    .hasMessageContaining("candidateId must not be null");
        }

        @Test
        @DisplayName("should reject null DestinationTrackCandidate with NullPointerException")
        void shouldRejectNullCandidate() {
            assertThatThrownBy(() -> new CandidateTrackFeatures(
                    "cand-1", "title", "title", "title", "artist", "artist",
                    null, null, false, false, null, null,
                    CandidateVersionEvidence.empty(), OfficialSignalEvidence.none(), null
            )).isInstanceOf(NullPointerException.class)
                    .hasMessageContaining("DestinationTrackCandidate must not be null");
        }

        @Test
        @DisplayName("should populate optional accessors accurately when fields are present")
        void shouldPopulateOptionalAccessors() {
            DestinationTrackCandidate c = candidate("cand-1", "PT4M8S");
            CandidateTrackFeatures features = new CandidateTrackFeatures(
                    c.id(), c.title(), "daft punk - get lucky", "get lucky",
                    c.channelOrArtist(), "daft punk",
                    c.duration(), 248000L, true, true, c.description(), c.publishedAt(),
                    CandidateVersionEvidence.empty(), OfficialSignalEvidence.none(), c
            );

            assertThat(features.candidateId()).isEqualTo("cand-1");
            assertThat(features.hasDuration()).isTrue();
            assertThat(features.hasDescription()).isTrue();
            assertThat(features.getDuration()).contains(Duration.ofSeconds(248));
            assertThat(features.getDurationMs()).contains(248000L);
            assertThat(features.getDescription()).contains("Official music video");
            assertThat(features.getPublishedAt()).contains("2013-04-19");
        }

        @Test
        @DisplayName("should handle missing duration and description cleanly")
        void shouldHandleMissingDurationAndDescription() {
            DestinationTrackCandidate c = new DestinationTrackCandidate(
                    "cand-2", "Title", "Channel", null, null, null, (Duration) null
            );
            CandidateTrackFeatures features = new CandidateTrackFeatures(
                    c.id(), c.title(), "title", "title",
                    c.channelOrArtist(), "channel",
                    null, null, false, false, null, null,
                    CandidateVersionEvidence.empty(), OfficialSignalEvidence.none(), c
            );

            assertThat(features.hasDuration()).isFalse();
            assertThat(features.hasDescription()).isFalse();
            assertThat(features.duration()).isNull();
            assertThat(features.durationMs()).isNull();
            assertThat(features.description()).isNull();
            assertThat(features.publishedAt()).isNull();
            assertThat(features.getDuration()).isEmpty();
            assertThat(features.getDurationMs()).isEmpty();
            assertThat(features.getDescription()).isEmpty();
        }
    }
}
