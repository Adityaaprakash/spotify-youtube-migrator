package com.spotifyyoutube.migrator.matching.engine.scorer;

import com.spotifyyoutube.migrator.matching.engine.MatchScoreBreakdown;
import com.spotifyyoutube.migrator.matching.engine.feature.CandidateTrackFeatures;
import com.spotifyyoutube.migrator.matching.engine.feature.CandidateVersionEvidence;
import com.spotifyyoutube.migrator.matching.engine.feature.OfficialSignalEvidence;
import com.spotifyyoutube.migrator.matching.engine.feature.SourceTrackFeatures;
import com.spotifyyoutube.migrator.matching.engine.feature.VersionIndicator;
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
import java.util.Locale;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("CandidateSimilarityScorer Integration Tests (Phase 7C + 7D + 7E + 7F Seam)")
class CandidateSimilarityScorerIntegrationTest {

    private CandidateSimilarityScorer candidateScorer;

    @BeforeEach
    void setUp() {
        candidateScorer = new DefaultCandidateSimilarityScorer();
    }

    private SourceTrackFeatures source(String title, String artist, List<String> featured) {
        return source(title, artist, featured, "Random Access Memories", Duration.ofMillis(248000));
    }

    private SourceTrackFeatures source(String title, String artist, List<String> featured, String rawAlbum, Duration duration) {
        String cleanPrimary = artist != null ? artist.trim().toLowerCase(Locale.ROOT) : "";
        Long durationMs = duration != null ? duration.toMillis() : null;
        String normalizedAlbum = rawAlbum != null ? rawAlbum.trim().toLowerCase(Locale.ROOT) : "";
        NormalizedTrackMetadata metadata = new NormalizedTrackMetadata(
                RawTrackMetadata.of(title, artist, rawAlbum, durationMs),
                title != null ? title.toLowerCase(Locale.ROOT) : "",
                new NormalizedArtistCredit(cleanPrimary, featured != null ? featured : List.of()),
                List.of(cleanPrimary),
                featured != null ? featured : List.of(),
                normalizedAlbum,
                duration,
                new TokenizedTitle(title != null ? title : "", title != null ? title : "", false, Set.of(), null, null, Set.of(), List.of(), List.of(), List.of())
        );
        return new SourceTrackFeatures(
                "spotify:track:123", title != null ? title.toLowerCase(Locale.ROOT) : "", title != null ? title : "", cleanPrimary,
                List.of(cleanPrimary), featured != null ? featured : List.of(),
                normalizedAlbum, duration, durationMs,
                !normalizedAlbum.isEmpty(), duration != null, !cleanPrimary.isEmpty(), Set.of(), true, false, false, false, metadata
        );
    }

    private CandidateTrackFeatures candidate(String id, String rawTitle, String channel) {
        return candidate(id, rawTitle, channel, "Description", Duration.ofMillis(248000));
    }

    private CandidateTrackFeatures candidate(String id, String rawTitle, String channel, String description, Duration duration) {
        Long durationMs = duration != null ? duration.toMillis() : null;
        DestinationTrackCandidate candidate = new DestinationTrackCandidate(
                id, rawTitle, channel, description, "https://thumb.jpg", "2021-01-01", duration
        );
        return new CandidateTrackFeatures(
                id, rawTitle, rawTitle != null ? rawTitle.toLowerCase(Locale.ROOT) : "", rawTitle != null ? rawTitle : "",
                channel, channel != null ? channel.toLowerCase(Locale.ROOT) : "",
                duration, durationMs, duration != null, description != null, description, "2021-01-01",
                CandidateVersionEvidence.empty(), OfficialSignalEvidence.none(), candidate
        );
    }

    @Test
    @DisplayName("should compute high title, artist, and duration scores for matching candidate")
    void shouldComputeHighScoresForMatchingCandidate() {
        SourceTrackFeatures s = source("Get Lucky", "Daft Punk", List.of("pharrell williams"));
        CandidateTrackFeatures c = candidate("vid-1", "Daft Punk - Get Lucky", "Daft Punk");

        MatchScoreBreakdown breakdown = candidateScorer.scoreCandidate(s, c);

        assertThat(breakdown).isNotNull();
        assertThat(breakdown.titleScore()).isNotNull();
        assertThat(breakdown.artistScore()).isNotNull();
        assertThat(breakdown.durationScore()).isNotNull();

        assertThat(breakdown.titleScore()).isGreaterThanOrEqualTo(0.95);
        assertThat(breakdown.artistScore()).isEqualTo(1.0);
        assertThat(breakdown.durationScore()).isEqualTo(1.0);

        // Album is null because candidate description lacks album evidence
        assertThat(breakdown.albumScore()).isNull();

        // Official signal remains honestly null (Phase 7G scope)
        assertThat(breakdown.officialSignalScore()).isNull();

        // Phase 7F version penalty is 0.0 for matching recording
        assertThat(breakdown.versionPenalty()).isEqualTo(0.0);
        assertThat(breakdown.detailedPenalties()).isEmpty();
    }

    @Test
    @DisplayName("should populate albumScore when candidate description provides album evidence")
    void shouldPopulateAlbumScoreWhenDescriptionProvidesAlbum() {
        SourceTrackFeatures s = source("Get Lucky", "Daft Punk", List.of(), "Random Access Memories", Duration.ofMillis(248000));
        CandidateTrackFeatures c = candidate("vid-album", "Daft Punk - Get Lucky", "Daft Punk",
                "Provided to YouTube by Columbia\n\nAlbum: Random Access Memories\n\n℗ 2013 Daft Life Limited", Duration.ofMillis(248000));

        MatchScoreBreakdown breakdown = candidateScorer.scoreCandidate(s, c);

        assertThat(breakdown.albumScore()).isNotNull();
        assertThat(breakdown.albumScore()).isEqualTo(1.0);
        assertThat(breakdown.durationScore()).isEqualTo(1.0);
    }

    @Test
    @DisplayName("should isolate title mismatch from matching artist (different song by same artist)")
    void shouldIsolateTitleMismatchFromMatchingArtist() {
        SourceTrackFeatures s = source("Instant Crush", "Daft Punk", List.of());
        CandidateTrackFeatures c = candidate("vid-2", "Daft Punk - Get Lucky", "Daft Punk");

        MatchScoreBreakdown breakdown = candidateScorer.scoreCandidate(s, c);

        assertThat(breakdown.artistScore()).isEqualTo(1.0);
        assertThat(breakdown.titleScore()).isLessThan(0.40);
        assertThat(breakdown.durationScore()).isEqualTo(1.0);
    }

    @Test
    @DisplayName("should isolate artist mismatch from matching title (same song title by different artist)")
    void shouldIsolateArtistMismatchFromMatchingTitle() {
        SourceTrackFeatures s = source("Get Lucky", "Daft Punk", List.of());
        CandidateTrackFeatures c = candidate("vid-3", "Get Lucky", "Russian Red");

        MatchScoreBreakdown breakdown = candidateScorer.scoreCandidate(s, c);

        assertThat(breakdown.titleScore()).isEqualTo(1.0);
        assertThat(breakdown.artistScore()).isLessThan(0.50);
        assertThat(breakdown.durationScore()).isEqualTo(1.0);
    }

    @Test
    @DisplayName("should penalize duration mismatch while preserving matching title and artist")
    void shouldPenalizeDurationMismatch() {
        // 248s vs 350s (difference of 102s > 60s max threshold)
        SourceTrackFeatures s = source("Get Lucky", "Daft Punk", List.of(), "Random Access Memories", Duration.ofSeconds(248));
        CandidateTrackFeatures c = candidate("vid-extended", "Daft Punk - Get Lucky", "Daft Punk", "Description", Duration.ofSeconds(350));

        MatchScoreBreakdown breakdown = candidateScorer.scoreCandidate(s, c);

        assertThat(breakdown.titleScore()).isGreaterThanOrEqualTo(0.95);
        assertThat(breakdown.artistScore()).isEqualTo(1.0);
        assertThat(breakdown.durationScore()).isEqualTo(0.0);
    }

    @Test
    @DisplayName("should apply cover penalty to candidate while preserving title, artist, and duration scores")
    void shouldApplyCoverPenaltyWhilePreservingOtherScores() {
        SourceTrackFeatures s = source("Get Lucky", "Daft Punk", List.of());
        CandidateTrackFeatures c = candidateWithVersion("vid-cover", "Daft Punk - Get Lucky (Cover)", "Daft Punk",
                Set.of(VersionIndicator.COVER));

        MatchScoreBreakdown breakdown = candidateScorer.scoreCandidate(s, c);

        assertThat(breakdown.titleScore()).isGreaterThanOrEqualTo(0.95);
        assertThat(breakdown.artistScore()).isEqualTo(1.0);
        assertThat(breakdown.durationScore()).isEqualTo(1.0);
        assertThat(breakdown.versionPenalty()).isEqualTo(0.25);
        assertThat(breakdown.detailedPenalties()).containsEntry("COVER", 0.25);
    }

    @Test
    @DisplayName("should apply reaction penalty to candidate")
    void shouldApplyReactionPenaltyToCandidate() {
        SourceTrackFeatures s = source("Get Lucky", "Daft Punk", List.of());
        CandidateTrackFeatures c = candidateWithVersion("vid-reaction", "Daft Punk - Get Lucky (Reaction)", "Daft Punk",
                Set.of(VersionIndicator.REACTION));

        MatchScoreBreakdown breakdown = candidateScorer.scoreCandidate(s, c);

        assertThat(breakdown.versionPenalty()).isEqualTo(0.30);
        assertThat(breakdown.detailedPenalties()).containsEntry("REACTION", 0.30);
    }

    @Test
    @DisplayName("should apply live mismatch penalty when candidate is live but source is studio")
    void shouldApplyLiveMismatchPenaltyWhenSourceIsStudio() {
        SourceTrackFeatures s = source("Get Lucky", "Daft Punk", List.of());
        CandidateTrackFeatures c = candidateWithVersion("vid-live", "Daft Punk - Get Lucky (Live)", "Daft Punk",
                Set.of(VersionIndicator.LIVE));

        MatchScoreBreakdown breakdown = candidateScorer.scoreCandidate(s, c);

        assertThat(breakdown.versionPenalty()).isEqualTo(0.15);
        assertThat(breakdown.detailedPenalties()).containsEntry("LIVE_MISMATCH", 0.15);
    }

    @Test
    @DisplayName("should not penalize candidate when both source and candidate are live recordings")
    void shouldNotPenalizeWhenBothAreLive() {
        SourceTrackFeatures s = liveSource("Get Lucky (Live)", "Daft Punk");
        CandidateTrackFeatures c = candidateWithVersion("vid-live-match", "Daft Punk - Get Lucky (Live)", "Daft Punk",
                Set.of(VersionIndicator.LIVE));

        MatchScoreBreakdown breakdown = candidateScorer.scoreCandidate(s, c);

        assertThat(breakdown.versionPenalty()).isEqualTo(0.0);
        assertThat(breakdown.detailedPenalties()).isEmpty();
    }

    @Test
    @DisplayName("should combine multiple penalties additively in integration seam")
    void shouldCombineMultiplePenaltiesAdditively() {
        SourceTrackFeatures s = source("Get Lucky", "Daft Punk", List.of());
        CandidateTrackFeatures c = candidateWithVersion("vid-multi", "Get Lucky (Cover Reaction)", "Daft Punk",
                Set.of(VersionIndicator.COVER, VersionIndicator.REACTION));

        MatchScoreBreakdown breakdown = candidateScorer.scoreCandidate(s, c);

        assertThat(breakdown.versionPenalty()).isEqualTo(0.55);
        assertThat(breakdown.detailedPenalties()).containsEntry("COVER", 0.25);
        assertThat(breakdown.detailedPenalties()).containsEntry("REACTION", 0.30);
    }

    @Test
    @DisplayName("should preserve missing evidence honestly as null without synthetic coercion")
    void shouldPreserveMissingEvidenceHonestly() {
        SourceTrackFeatures s = source("", "Daft Punk", List.of(), "", null);
        CandidateTrackFeatures c = candidate("vid-4", "Song Title", "", null, null);

        MatchScoreBreakdown breakdown = candidateScorer.scoreCandidate(s, c);

        // Source title is empty -> titleScore is null
        assertThat(breakdown.titleScore()).isNull();
        assertThat(breakdown.hasTitleScore()).isFalse();

        // Candidate channel is empty -> artistScore is null
        assertThat(breakdown.artistScore()).isNull();
        assertThat(breakdown.hasArtistScore()).isFalse();

        // Durations are null -> durationScore is null
        assertThat(breakdown.durationScore()).isNull();
        assertThat(breakdown.hasDurationScore()).isFalse();

        // Album is null -> albumScore is null
        assertThat(breakdown.albumScore()).isNull();
        assertThat(breakdown.hasAlbumScore()).isFalse();

        // Version penalty is 0.0 (no version discrepancies)
        assertThat(breakdown.versionPenalty()).isEqualTo(0.0);
        assertThat(breakdown.detailedPenalties()).isEmpty();
    }

    @Test
    @DisplayName("should reject null source or candidate with NullPointerException")
    void shouldRejectNullInputs() {
        SourceTrackFeatures s = source("Title", "Artist", List.of());
        CandidateTrackFeatures c = candidate("vid-5", "Title", "Artist");

        assertThatThrownBy(() -> candidateScorer.scoreCandidate(null, c))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> candidateScorer.scoreCandidate(s, null))
                .isInstanceOf(NullPointerException.class);
    }

    private SourceTrackFeatures liveSource(String title, String artist) {
        String cleanPrimary = artist != null ? artist.trim().toLowerCase(Locale.ROOT) : "";
        NormalizedTrackMetadata metadata = new NormalizedTrackMetadata(
                RawTrackMetadata.of(title, artist, "Album", 248000L),
                title != null ? title.toLowerCase(Locale.ROOT) : "",
                new NormalizedArtistCredit(cleanPrimary, List.of()),
                List.of(cleanPrimary),
                List.of(),
                "album",
                Duration.ofMillis(248000),
                new TokenizedTitle(title != null ? title : "", title != null ? title : "", false, Set.of(), null, null, Set.of(), List.of(), List.of(), List.of())
        );
        return new SourceTrackFeatures(
                "spotify:track:live", title != null ? title.toLowerCase(Locale.ROOT) : "", title != null ? title : "", cleanPrimary,
                List.of(cleanPrimary), List.of(),
                "album", Duration.ofMillis(248000), 248000L,
                true, true, !cleanPrimary.isEmpty(), Set.of(VersionIndicator.LIVE), false, true, false, false, metadata
        );
    }

    private CandidateTrackFeatures candidateWithVersion(String id, String rawTitle, String channel, Set<VersionIndicator> indicators) {
        Long durationMs = 248000L;
        DestinationTrackCandidate candidate = new DestinationTrackCandidate(
                id, rawTitle, channel, "Description", "https://thumb.jpg", "2021-01-01", Duration.ofMillis(durationMs)
        );
        CandidateVersionEvidence versionEvidence = new CandidateVersionEvidence(
                indicators, List.of(), List.of(), false, false
        );
        return new CandidateTrackFeatures(
                id, rawTitle, rawTitle != null ? rawTitle.toLowerCase(Locale.ROOT) : "", rawTitle != null ? rawTitle : "",
                channel, channel != null ? channel.toLowerCase(Locale.ROOT) : "",
                Duration.ofMillis(durationMs), durationMs, true, true, "Description", "2021-01-01",
                versionEvidence, OfficialSignalEvidence.none(), candidate
        );
    }
}
