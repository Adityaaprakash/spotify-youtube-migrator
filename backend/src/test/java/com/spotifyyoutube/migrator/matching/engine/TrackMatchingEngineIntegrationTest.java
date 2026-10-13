package com.spotifyyoutube.migrator.matching.engine;

import com.spotifyyoutube.migrator.matching.normalization.NormalizedArtistCredit;
import com.spotifyyoutube.migrator.matching.normalization.NormalizedTrackMetadata;
import com.spotifyyoutube.migrator.matching.normalization.RawTrackMetadata;
import com.spotifyyoutube.migrator.matching.normalization.TokenizedTitle;
import com.spotifyyoutube.migrator.migration.application.destination.model.DestinationTrackCandidate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("TrackMatchingEngine Integration Tests")
class TrackMatchingEngineIntegrationTest {

    private TrackMatchingEngine matchingEngine;

    @BeforeEach
    void setUp() {
        matchingEngine = new DefaultTrackMatchingEngine();
    }

    @Test
    @DisplayName("should execute end-to-end pipeline and select unambiguous official match")
    void shouldExecuteEndToEndPipelineAndSelectMatch() {
        NormalizedTrackMetadata sourceTrack = createSourceTrack("Get Lucky", "Daft Punk", "Random Access Memories", 248000L);

        DestinationTrackCandidate officialCandidate = new DestinationTrackCandidate(
                "cand-official",
                "Daft Punk - Get Lucky (Official Audio)",
                "Daft Punk - Topic",
                "Provided to YouTube by Columbia\n\nAlbum: Random Access Memories\n\n℗ 2013 Daft Life Limited",
                "https://thumb.jpg",
                "2013-04-19",
                Duration.ofMillis(248000)
        );

        DestinationTrackCandidate liveCandidate = new DestinationTrackCandidate(
                "cand-live",
                "Daft Punk - Get Lucky (Live at Grammy Awards 2014)",
                "Daft Punk",
                "Live performance live concert recording",
                "https://thumb.jpg",
                "2014-01-26",
                Duration.ofMillis(260000)
        );

        DestinationTrackCandidate differentSong = new DestinationTrackCandidate(
                "cand-diff",
                "Daft Punk - One More Time",
                "Daft Punk - Topic",
                "Provided to YouTube by Daft Life Ltd",
                "https://thumb.jpg",
                "2001-03-12",
                Duration.ofMillis(320000)
        );

        TrackMatchRequest request = new TrackMatchRequest(
                "spotify:track:daftlucky",
                sourceTrack,
                List.of(differentSong, liveCandidate, officialCandidate)
        );

        TrackMatchResult result = matchingEngine.matchTrack(request);

        assertThat(result).isNotNull();
        assertThat(result.decision()).isEqualTo(MatchDecision.MATCH);
        assertThat(result.confidence()).isEqualTo(MatchConfidence.EXCELLENT);
        assertThat(result.selectedCandidate()).isNotNull();
        assertThat(result.selectedCandidate().id()).isEqualTo("cand-official");

        // Verify ranked evaluations order
        assertThat(result.candidateEvaluations()).hasSize(3);
        assertThat(result.candidateEvaluations().get(0).candidateId()).isEqualTo("cand-official");
        assertThat(result.candidateEvaluations().get(1).candidateId()).isEqualTo("cand-live");
        assertThat(result.candidateEvaluations().get(2).candidateId()).isEqualTo("cand-diff");

        // Verify score breakdown on official candidate
        assertThat(result.scoreBreakdown()).isNotNull();
        assertThat(result.scoreBreakdown().titleScore()).isGreaterThanOrEqualTo(0.95);
        assertThat(result.scoreBreakdown().artistScore()).isEqualTo(1.0);
        assertThat(result.scoreBreakdown().durationScore()).isEqualTo(1.0);
        assertThat(result.scoreBreakdown().albumScore()).isEqualTo(1.0);
        assertThat(result.scoreBreakdown().officialSignalScore()).isEqualTo(1.0);
        assertThat(result.scoreBreakdown().versionPenalty()).isEqualTo(0.0);

        // Verify composite score
        assertThat(result.score()).isGreaterThanOrEqualTo(0.95);
        assertThat(result.matchReasons()).isNotEmpty();
    }

    @Test
    @DisplayName("should handle empty candidate discovery result with clean NO_MATCH")
    void shouldHandleEmptyCandidatesWithNoMatch() {
        NormalizedTrackMetadata sourceTrack = createSourceTrack("Obscure Indie Song", "Unknown Artist", "Album", 180000L);
        TrackMatchRequest request = new TrackMatchRequest("spotify:track:obscure", sourceTrack, List.of());

        TrackMatchResult result = matchingEngine.matchTrack(request);

        assertThat(result).isNotNull();
        assertThat(result.decision()).isEqualTo(MatchDecision.NO_MATCH);
        assertThat(result.selectedCandidate()).isNull();
        assertThat(result.hasSelectedCandidate()).isFalse();
        assertThat(result.candidateEvaluations()).isEmpty();
        assertThat(result.matchReasons()).anyMatch(r -> r.contains("No destination candidates discovered"));
    }

    @Test
    @DisplayName("should require manual review when only candidate is a cover version")
    void shouldRequireReviewWhenCandidateIsCover() {
        NormalizedTrackMetadata sourceTrack = createSourceTrack("Billie Jean", "Michael Jackson", "Thriller", 294000L);

        DestinationTrackCandidate coverCandidate = new DestinationTrackCandidate(
                "cand-cover",
                "Michael Jackson - Billie Jean (Acoustic Cover)",
                "Acoustic Covers Channel",
                "Cover performed by indie musician",
                "https://thumb.jpg",
                "2020-05-10",
                Duration.ofMillis(294000)
        );

        TrackMatchRequest request = new TrackMatchRequest(
                "spotify:track:billiejean",
                sourceTrack,
                List.of(coverCandidate)
        );

        TrackMatchResult result = matchingEngine.matchTrack(request);

        assertThat(result).isNotNull();
        assertThat(result.decision()).isEqualTo(MatchDecision.REVIEW_REQUIRED);
        assertThat(result.selectedCandidate()).isNotNull();
        assertThat(result.selectedCandidate().id()).isEqualTo("cand-cover");
        assertThat(result.matchReasons()).anyMatch(r -> r.contains("version discrepancy penalty"));
    }

    @Test
    @DisplayName("should require manual review when leading candidates are too close (ambiguous match)")
    void shouldRequireReviewWhenLeadingCandidatesAreAmbiguous() {
        NormalizedTrackMetadata sourceTrack = createSourceTrack("Starboy", "The Weeknd", "Starboy", 230000L);

        DestinationTrackCandidate cand1 = new DestinationTrackCandidate(
                "cand-audio",
                "The Weeknd - Starboy (Official Audio)",
                "The Weeknd - Topic",
                "Provided to YouTube by Universal Music Group",
                "https://thumb.jpg",
                "2016-09-22",
                Duration.ofMillis(230000)
        );

        DestinationTrackCandidate cand2 = new DestinationTrackCandidate(
                "cand-video",
                "The Weeknd - Starboy (Official Music Video)",
                "The Weeknd - Topic",
                "Provided to YouTube by Universal Music Group",
                "https://thumb.jpg",
                "2016-09-28",
                Duration.ofMillis(231000)
        );

        TrackMatchRequest request = new TrackMatchRequest(
                "spotify:track:starboy",
                sourceTrack,
                List.of(cand1, cand2)
        );

        TrackMatchResult result = matchingEngine.matchTrack(request);

        assertThat(result).isNotNull();
        assertThat(result.decision()).isEqualTo(MatchDecision.REVIEW_REQUIRED);
        assertThat(result.matchReasons()).anyMatch(r -> r.contains("Ambiguous match"));
    }

    @Test
    @DisplayName("should reject null TrackMatchRequest with NullPointerException")
    void shouldRejectNullRequest() {
        assertThatThrownBy(() -> matchingEngine.matchTrack(null))
                .isInstanceOf(NullPointerException.class);
    }

    private NormalizedTrackMetadata createSourceTrack(String title, String primaryArtist, String album, Long durationMs) {
        Duration duration = durationMs != null ? Duration.ofMillis(durationMs) : null;
        return new NormalizedTrackMetadata(
                RawTrackMetadata.of(title, primaryArtist, album, durationMs),
                title.toLowerCase(),
                new NormalizedArtistCredit(primaryArtist.toLowerCase(), List.of()),
                List.of(primaryArtist.toLowerCase()),
                List.of(),
                album.toLowerCase(),
                duration,
                new TokenizedTitle(title, title, false, Set.of(), null, null, Set.of(), List.of(), List.of(), List.of())
        );
    }
}
