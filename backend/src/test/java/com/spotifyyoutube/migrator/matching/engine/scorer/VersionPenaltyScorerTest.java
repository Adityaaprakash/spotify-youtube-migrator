package com.spotifyyoutube.migrator.matching.engine.scorer;

import com.spotifyyoutube.migrator.matching.engine.feature.CandidateTrackFeatures;
import com.spotifyyoutube.migrator.matching.engine.feature.CandidateVersionEvidence;
import com.spotifyyoutube.migrator.matching.engine.feature.DefaultTrackFeatureExtractor;
import com.spotifyyoutube.migrator.matching.engine.feature.OfficialSignalEvidence;
import com.spotifyyoutube.migrator.matching.engine.feature.SourceTrackFeatures;
import com.spotifyyoutube.migrator.matching.engine.feature.TrackFeatureExtractor;
import com.spotifyyoutube.migrator.matching.engine.feature.VersionIndicator;
import com.spotifyyoutube.migrator.matching.normalization.NormalizedArtistCredit;
import com.spotifyyoutube.migrator.matching.normalization.NormalizedTrackMetadata;
import com.spotifyyoutube.migrator.matching.normalization.RawTrackMetadata;
import com.spotifyyoutube.migrator.matching.normalization.TokenizedTitle;
import com.spotifyyoutube.migrator.migration.application.destination.model.DestinationTrackCandidate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("VersionPenaltyScorer Tests (Phase 7F)")
class VersionPenaltyScorerTest {

    private DefaultVersionPenaltyScorer scorer;
    private TrackFeatureExtractor featureExtractor;

    @BeforeEach
    void setUp() {
        scorer = new DefaultVersionPenaltyScorer();
        featureExtractor = new DefaultTrackFeatureExtractor();
    }

    private SourceTrackFeatures studioSource(String title, String artist) {
        return createSource(title, artist, Set.of(), false, false, false);
    }

    private SourceTrackFeatures liveSource(String title, String artist) {
        return createSource(title, artist, Set.of(VersionIndicator.LIVE), true, false, false);
    }

    private SourceTrackFeatures remixSource(String title, String artist) {
        return createSource(title, artist, Set.of(VersionIndicator.REMIX), false, false, false);
    }

    private SourceTrackFeatures acousticSource(String title, String artist) {
        return createSource(title, artist, Set.of(VersionIndicator.ACOUSTIC), false, false, true);
    }

    private SourceTrackFeatures createSource(
            String title,
            String artist,
            Set<VersionIndicator> indicators,
            boolean isLive,
            boolean isRemaster,
            boolean isAcoustic
    ) {
        String cleanPrimary = artist != null ? artist.trim().toLowerCase(Locale.ROOT) : "";
        NormalizedTrackMetadata metadata = new NormalizedTrackMetadata(
                RawTrackMetadata.of(title, artist, "Album", 240000L),
                title != null ? title.toLowerCase(Locale.ROOT) : "",
                new NormalizedArtistCredit(cleanPrimary, List.of()),
                List.of(cleanPrimary),
                List.of(),
                "album",
                Duration.ofMillis(240000),
                new TokenizedTitle(title != null ? title : "", title != null ? title : "", true, Set.of(), null, null, Set.of(), List.of(), List.of(), List.of())
        );
        return new SourceTrackFeatures(
                "spotify:track:123",
                title != null ? title.toLowerCase(Locale.ROOT) : "",
                title != null ? title : "",
                cleanPrimary,
                List.of(cleanPrimary),
                List.of(),
                "album",
                Duration.ofMillis(240000),
                240000L,
                true,
                true,
                !cleanPrimary.isEmpty(),
                indicators,
                indicators.isEmpty(),
                isLive,
                isRemaster,
                isAcoustic,
                metadata
        );
    }

    private CandidateTrackFeatures candidateWithIndicators(String title, Set<VersionIndicator> indicators) {
        DestinationTrackCandidate candidate = new DestinationTrackCandidate(
                "vid-123",
                title,
                "Artist Channel",
                "Description",
                "https://thumb.jpg",
                "2021-01-01",
                Duration.ofMillis(240000)
        );
        CandidateVersionEvidence versionEvidence = new CandidateVersionEvidence(
                indicators,
                List.of(),
                List.of(),
                false,
                false
        );
        return new CandidateTrackFeatures(
                "vid-123",
                title,
                title.toLowerCase(Locale.ROOT),
                title,
                "Artist Channel",
                "artist channel",
                Duration.ofMillis(240000),
                240000L,
                true,
                true,
                "Description",
                "2021-01-01",
                versionEvidence,
                OfficialSignalEvidence.none(),
                candidate
        );
    }

    private CandidateTrackFeatures extractCandidate(String title, String channel) {
        DestinationTrackCandidate candidate = new DestinationTrackCandidate(
                "vid-123",
                title,
                channel,
                "Description",
                "https://thumb.jpg",
                "2021-01-01",
                Duration.ofMillis(240000)
        );
        return featureExtractor.extractCandidateFeatures(candidate);
    }

    @Nested
    @DisplayName("Individual Variant Penalties")
    class IndividualVariantPenalties {

        @Test
        @DisplayName("should apply 0.25 penalty for cover version")
        void shouldApplyCoverPenalty() {
            SourceTrackFeatures source = studioSource("Get Lucky", "Daft Punk");
            CandidateTrackFeatures candidate = candidateWithIndicators("Daft Punk - Get Lucky (Cover)", Set.of(VersionIndicator.COVER));

            Double penalty = scorer.calculatePenalty(source, candidate);
            Map<String, Double> detailed = scorer.calculateDetailedPenalties(source, candidate);

            assertThat(penalty).isEqualTo(0.25);
            assertThat(detailed).containsEntry("COVER", 0.25);
            assertThat(detailed).hasSize(1);
        }

        @Test
        @DisplayName("should apply 0.30 penalty for reaction video")
        void shouldApplyReactionPenalty() {
            SourceTrackFeatures source = studioSource("Get Lucky", "Daft Punk");
            CandidateTrackFeatures candidate = candidateWithIndicators("Daft Punk - Get Lucky (Reaction)", Set.of(VersionIndicator.REACTION));

            Double penalty = scorer.calculatePenalty(source, candidate);
            Map<String, Double> detailed = scorer.calculateDetailedPenalties(source, candidate);

            assertThat(penalty).isEqualTo(0.30);
            assertThat(detailed).containsEntry("REACTION", 0.30);
            assertThat(detailed).hasSize(1);
        }

        @Test
        @DisplayName("should apply 0.30 penalty for karaoke track")
        void shouldApplyKaraokePenalty() {
            SourceTrackFeatures source = studioSource("Get Lucky", "Daft Punk");
            CandidateTrackFeatures candidate = candidateWithIndicators("Daft Punk - Get Lucky (Karaoke)", Set.of(VersionIndicator.KARAOKE));

            Double penalty = scorer.calculatePenalty(source, candidate);
            Map<String, Double> detailed = scorer.calculateDetailedPenalties(source, candidate);

            assertThat(penalty).isEqualTo(0.30);
            assertThat(detailed).containsEntry("KARAOKE", 0.30);
            assertThat(detailed).hasSize(1);
        }

        @Test
        @DisplayName("should apply 0.20 penalty for nightcore version")
        void shouldApplyNightcorePenalty() {
            SourceTrackFeatures source = studioSource("Get Lucky", "Daft Punk");
            CandidateTrackFeatures candidate = candidateWithIndicators("Daft Punk - Get Lucky (Nightcore)", Set.of(VersionIndicator.NIGHTCORE));

            Double penalty = scorer.calculatePenalty(source, candidate);
            Map<String, Double> detailed = scorer.calculateDetailedPenalties(source, candidate);

            assertThat(penalty).isEqualTo(0.20);
            assertThat(detailed).containsEntry("NIGHTCORE", 0.20);
            assertThat(detailed).hasSize(1);
        }

        @Test
        @DisplayName("should apply 0.15 penalty for sped-up version")
        void shouldApplySpedUpPenalty() {
            SourceTrackFeatures source = studioSource("Get Lucky", "Daft Punk");
            CandidateTrackFeatures candidate = candidateWithIndicators("Daft Punk - Get Lucky (Sped Up)", Set.of(VersionIndicator.SPED_UP));

            Double penalty = scorer.calculatePenalty(source, candidate);
            Map<String, Double> detailed = scorer.calculateDetailedPenalties(source, candidate);

            assertThat(penalty).isEqualTo(0.15);
            assertThat(detailed).containsEntry("SPED_UP", 0.15);
            assertThat(detailed).hasSize(1);
        }

        @Test
        @DisplayName("should apply 0.15 penalty for slowed version")
        void shouldApplySlowedPenalty() {
            SourceTrackFeatures source = studioSource("Get Lucky", "Daft Punk");
            CandidateTrackFeatures candidate = candidateWithIndicators("Daft Punk - Get Lucky (Slowed)", Set.of(VersionIndicator.SLOWED));

            Double penalty = scorer.calculatePenalty(source, candidate);
            Map<String, Double> detailed = scorer.calculateDetailedPenalties(source, candidate);

            assertThat(penalty).isEqualTo(0.15);
            assertThat(detailed).containsEntry("SLOWED", 0.15);
            assertThat(detailed).hasSize(1);
        }

        @Test
        @DisplayName("should apply 0.15 penalty for live mismatch when source is studio")
        void shouldApplyLiveMismatchPenalty() {
            SourceTrackFeatures source = studioSource("Get Lucky", "Daft Punk");
            CandidateTrackFeatures candidate = candidateWithIndicators("Daft Punk - Get Lucky (Live)", Set.of(VersionIndicator.LIVE));

            Double penalty = scorer.calculatePenalty(source, candidate);
            Map<String, Double> detailed = scorer.calculateDetailedPenalties(source, candidate);

            assertThat(penalty).isEqualTo(0.15);
            assertThat(detailed).containsEntry("LIVE_MISMATCH", 0.15);
            assertThat(detailed).hasSize(1);
        }

        @Test
        @DisplayName("should apply 0.15 penalty for remix mismatch when source is original")
        void shouldApplyRemixMismatchPenalty() {
            SourceTrackFeatures source = studioSource("Get Lucky", "Daft Punk");
            CandidateTrackFeatures candidate = candidateWithIndicators("Daft Punk - Get Lucky (Remix)", Set.of(VersionIndicator.REMIX));

            Double penalty = scorer.calculatePenalty(source, candidate);
            Map<String, Double> detailed = scorer.calculateDetailedPenalties(source, candidate);

            assertThat(penalty).isEqualTo(0.15);
            assertThat(detailed).containsEntry("REMIX_MISMATCH", 0.15);
            assertThat(detailed).hasSize(1);
        }

        @Test
        @DisplayName("should apply 0.10 penalty for acoustic mismatch when source is standard")
        void shouldApplyAcousticMismatchPenalty() {
            SourceTrackFeatures source = studioSource("Get Lucky", "Daft Punk");
            CandidateTrackFeatures candidate = candidateWithIndicators("Daft Punk - Get Lucky (Acoustic)", Set.of(VersionIndicator.ACOUSTIC));

            Double penalty = scorer.calculatePenalty(source, candidate);
            Map<String, Double> detailed = scorer.calculateDetailedPenalties(source, candidate);

            assertThat(penalty).isEqualTo(0.10);
            assertThat(detailed).containsEntry("ACOUSTIC_MISMATCH", 0.10);
            assertThat(detailed).hasSize(1);
        }

        @Test
        @DisplayName("should apply 0.15 penalty for instrumental mismatch when source is vocal")
        void shouldApplyInstrumentalMismatchPenalty() {
            SourceTrackFeatures source = studioSource("Get Lucky", "Daft Punk");
            CandidateTrackFeatures candidate = candidateWithIndicators("Daft Punk - Get Lucky (Instrumental)", Set.of(VersionIndicator.INSTRUMENTAL));

            Double penalty = scorer.calculatePenalty(source, candidate);
            Map<String, Double> detailed = scorer.calculateDetailedPenalties(source, candidate);

            assertThat(penalty).isEqualTo(0.15);
            assertThat(detailed).containsEntry("INSTRUMENTAL_MISMATCH", 0.15);
            assertThat(detailed).hasSize(1);
        }

        @Test
        @DisplayName("should apply 0.00 penalty for remaster")
        void shouldApplyZeroPenaltyForRemaster() {
            SourceTrackFeatures source = studioSource("Get Lucky", "Daft Punk");
            CandidateTrackFeatures candidate = candidateWithIndicators("Daft Punk - Get Lucky (2020 Remaster)", Set.of(VersionIndicator.REMASTER));

            Double penalty = scorer.calculatePenalty(source, candidate);
            Map<String, Double> detailed = scorer.calculateDetailedPenalties(source, candidate);

            assertThat(penalty).isEqualTo(0.0);
            assertThat(detailed).isEmpty();
        }
    }

    @Nested
    @DisplayName("Source Variant Alignment")
    class SourceVariantAlignment {

        @Test
        @DisplayName("should not penalize when source is live and candidate is live")
        void shouldNotPenalizeMatchingLive() {
            SourceTrackFeatures source = liveSource("Get Lucky (Live)", "Daft Punk");
            CandidateTrackFeatures candidate = candidateWithIndicators("Daft Punk - Get Lucky (Live)", Set.of(VersionIndicator.LIVE));

            Double penalty = scorer.calculatePenalty(source, candidate);
            Map<String, Double> detailed = scorer.calculateDetailedPenalties(source, candidate);

            assertThat(penalty).isEqualTo(0.0);
            assertThat(detailed).isEmpty();
        }

        @Test
        @DisplayName("should penalize when source is live but candidate is studio")
        void shouldPenalizeWhenSourceLiveAndCandidateStudio() {
            SourceTrackFeatures source = liveSource("Get Lucky (Live)", "Daft Punk");
            CandidateTrackFeatures candidate = candidateWithIndicators("Daft Punk - Get Lucky", Set.of());

            Double penalty = scorer.calculatePenalty(source, candidate);
            Map<String, Double> detailed = scorer.calculateDetailedPenalties(source, candidate);

            assertThat(penalty).isEqualTo(0.15);
            assertThat(detailed).containsEntry("LIVE_MISMATCH", 0.15);
        }

        @Test
        @DisplayName("should not penalize when source is remix and candidate is remix")
        void shouldNotPenalizeMatchingRemix() {
            SourceTrackFeatures source = remixSource("Get Lucky (Remix)", "Daft Punk");
            CandidateTrackFeatures candidate = candidateWithIndicators("Daft Punk - Get Lucky (Remix)", Set.of(VersionIndicator.REMIX));

            Double penalty = scorer.calculatePenalty(source, candidate);
            Map<String, Double> detailed = scorer.calculateDetailedPenalties(source, candidate);

            assertThat(penalty).isEqualTo(0.0);
            assertThat(detailed).isEmpty();
        }

        @Test
        @DisplayName("should penalize when source is remix but candidate is original")
        void shouldPenalizeWhenSourceRemixAndCandidateOriginal() {
            SourceTrackFeatures source = remixSource("Get Lucky (Remix)", "Daft Punk");
            CandidateTrackFeatures candidate = candidateWithIndicators("Daft Punk - Get Lucky", Set.of());

            Double penalty = scorer.calculatePenalty(source, candidate);
            Map<String, Double> detailed = scorer.calculateDetailedPenalties(source, candidate);

            assertThat(penalty).isEqualTo(0.15);
            assertThat(detailed).containsEntry("REMIX_MISMATCH", 0.15);
        }

        @Test
        @DisplayName("should not penalize when source is acoustic and candidate is acoustic")
        void shouldNotPenalizeMatchingAcoustic() {
            SourceTrackFeatures source = acousticSource("Get Lucky (Acoustic)", "Daft Punk");
            CandidateTrackFeatures candidate = candidateWithIndicators("Daft Punk - Get Lucky (Acoustic)", Set.of(VersionIndicator.ACOUSTIC));

            Double penalty = scorer.calculatePenalty(source, candidate);
            Map<String, Double> detailed = scorer.calculateDetailedPenalties(source, candidate);

            assertThat(penalty).isEqualTo(0.0);
            assertThat(detailed).isEmpty();
        }

        @Test
        @DisplayName("should penalize when source is acoustic but candidate is standard")
        void shouldPenalizeWhenSourceAcousticAndCandidateStandard() {
            SourceTrackFeatures source = acousticSource("Get Lucky (Acoustic)", "Daft Punk");
            CandidateTrackFeatures candidate = candidateWithIndicators("Daft Punk - Get Lucky", Set.of());

            Double penalty = scorer.calculatePenalty(source, candidate);
            Map<String, Double> detailed = scorer.calculateDetailedPenalties(source, candidate);

            assertThat(penalty).isEqualTo(0.10);
            assertThat(detailed).containsEntry("ACOUSTIC_MISMATCH", 0.10);
        }
    }

    @Nested
    @DisplayName("Multiple Simultaneous Variants and Score Bounds")
    class MultipleSimultaneousVariantsAndScoreBounds {

        @Test
        @DisplayName("should additively combine cover and reaction penalties")
        void shouldCombineCoverAndReaction() {
            SourceTrackFeatures source = studioSource("Get Lucky", "Daft Punk");
            CandidateTrackFeatures candidate = candidateWithIndicators(
                    "Daft Punk - Get Lucky (Cover Reaction)",
                    Set.of(VersionIndicator.COVER, VersionIndicator.REACTION)
            );

            Double penalty = scorer.calculatePenalty(source, candidate);
            Map<String, Double> detailed = scorer.calculateDetailedPenalties(source, candidate);

            // Cover (0.25) + Reaction (0.30) = 0.55
            assertThat(penalty).isEqualTo(0.55);
            assertThat(detailed).containsEntry("COVER", 0.25);
            assertThat(detailed).containsEntry("REACTION", 0.30);
            assertThat(detailed).hasSize(2);
        }

        @Test
        @DisplayName("should additively combine cover and live mismatch penalties")
        void shouldCombineCoverAndLiveMismatch() {
            SourceTrackFeatures source = studioSource("Get Lucky", "Daft Punk");
            CandidateTrackFeatures candidate = candidateWithIndicators(
                    "Daft Punk - Get Lucky (Live Cover)",
                    Set.of(VersionIndicator.COVER, VersionIndicator.LIVE)
            );

            Double penalty = scorer.calculatePenalty(source, candidate);
            Map<String, Double> detailed = scorer.calculateDetailedPenalties(source, candidate);

            // Cover (0.25) + Live (0.15) = 0.40
            assertThat(penalty).isEqualTo(0.40);
            assertThat(detailed).containsEntry("COVER", 0.25);
            assertThat(detailed).containsEntry("LIVE_MISMATCH", 0.15);
            assertThat(detailed).hasSize(2);
        }

        @Test
        @DisplayName("should cap total penalty at 1.0 when multiple penalties exceed 1.0")
        void shouldCapTotalPenaltyAtOne() {
            SourceTrackFeatures source = studioSource("Get Lucky", "Daft Punk");
            CandidateTrackFeatures candidate = candidateWithIndicators(
                    "Ultra Discrepancy",
                    Set.of(
                            VersionIndicator.COVER,       // 0.25
                            VersionIndicator.REACTION,    // 0.30
                            VersionIndicator.KARAOKE,     // 0.30
                            VersionIndicator.NIGHTCORE,   // 0.20
                            VersionIndicator.LIVE         // 0.15
                    )
            );

            Double penalty = scorer.calculatePenalty(source, candidate);
            Map<String, Double> detailed = scorer.calculateDetailedPenalties(source, candidate);

            // Total is 0.25 + 0.30 + 0.30 + 0.20 + 0.15 = 1.20 -> capped at 1.0
            assertThat(penalty).isEqualTo(1.0);
            assertThat(detailed).hasSize(5);
            assertThat(detailed).containsEntry("COVER", 0.25);
            assertThat(detailed).containsEntry("REACTION", 0.30);
            assertThat(detailed).containsEntry("KARAOKE", 0.30);
            assertThat(detailed).containsEntry("NIGHTCORE", 0.20);
            assertThat(detailed).containsEntry("LIVE_MISMATCH", 0.15);
        }

        @Test
        @DisplayName("should enforce lower bound of 0.0 when no discrepancies exist")
        void shouldEnforceLowerBoundZero() {
            SourceTrackFeatures source = studioSource("Get Lucky", "Daft Punk");
            CandidateTrackFeatures candidate = candidateWithIndicators("Daft Punk - Get Lucky", Set.of());

            Double penalty = scorer.calculatePenalty(source, candidate);
            Map<String, Double> detailed = scorer.calculateDetailedPenalties(source, candidate);

            assertThat(penalty).isEqualTo(0.0);
            assertThat(detailed).isEmpty();
        }
    }

    @Nested
    @DisplayName("False Positive Protection for Genuine Song Titles")
    class FalsePositiveProtectionForGenuineSongTitles {

        @Test
        @DisplayName("should not penalize Madonna - Live to Tell as a live recording")
        void shouldNotPenalizeLiveToTell() {
            CandidateTrackFeatures candidate = extractCandidate("Madonna - Live to Tell", "Madonna");
            SourceTrackFeatures source = studioSource("Live to Tell", "Madonna");

            Double penalty = scorer.calculatePenalty(source, candidate);
            Map<String, Double> detailed = scorer.calculateDetailedPenalties(source, candidate);

            assertThat(penalty).isEqualTo(0.0);
            assertThat(detailed).isEmpty();
        }

        @Test
        @DisplayName("should not penalize Wings - Live and Let Die as a live recording")
        void shouldNotPenalizeLiveAndLetDie() {
            CandidateTrackFeatures candidate = extractCandidate("Wings - Live and Let Die", "Wings");
            SourceTrackFeatures source = studioSource("Live and Let Die", "Wings");

            Double penalty = scorer.calculatePenalty(source, candidate);
            Map<String, Double> detailed = scorer.calculateDetailedPenalties(source, candidate);

            assertThat(penalty).isEqualTo(0.0);
            assertThat(detailed).isEmpty();
        }

        @Test
        @DisplayName("should not penalize Oasis - Live Forever as a live recording")
        void shouldNotPenalizeLiveForever() {
            CandidateTrackFeatures candidate = extractCandidate("Oasis - Live Forever", "Oasis");
            SourceTrackFeatures source = studioSource("Live Forever", "Oasis");

            Double penalty = scorer.calculatePenalty(source, candidate);
            Map<String, Double> detailed = scorer.calculateDetailedPenalties(source, candidate);

            assertThat(penalty).isEqualTo(0.0);
            assertThat(detailed).isEmpty();
        }

        @Test
        @DisplayName("should not penalize Bee Gees - Stayin' Alive as a live recording")
        void shouldNotPenalizeStayinAlive() {
            CandidateTrackFeatures candidate = extractCandidate("Bee Gees - Stayin' Alive", "Bee Gees");
            SourceTrackFeatures source = studioSource("Stayin' Alive", "Bee Gees");

            Double penalty = scorer.calculatePenalty(source, candidate);
            Map<String, Double> detailed = scorer.calculateDetailedPenalties(source, candidate);

            assertThat(penalty).isEqualTo(0.0);
            assertThat(detailed).isEmpty();
        }

        @Test
        @DisplayName("should not penalize The Strokes - Under Cover of Darkness as a cover")
        void shouldNotPenalizeUnderCoverOfDarkness() {
            CandidateTrackFeatures candidate = extractCandidate("The Strokes - Under Cover of Darkness", "The Strokes");
            SourceTrackFeatures source = studioSource("Under Cover of Darkness", "The Strokes");

            Double penalty = scorer.calculatePenalty(source, candidate);
            Map<String, Double> detailed = scorer.calculateDetailedPenalties(source, candidate);

            assertThat(penalty).isEqualTo(0.0);
            assertThat(detailed).isEmpty();
        }

        @Test
        @DisplayName("should not penalize Diana Ross - Chain Reaction as a reaction video")
        void shouldNotPenalizeChainReaction() {
            CandidateTrackFeatures candidate = extractCandidate("Diana Ross - Chain Reaction", "Diana Ross");
            SourceTrackFeatures source = studioSource("Chain Reaction", "Diana Ross");

            Double penalty = scorer.calculatePenalty(source, candidate);
            Map<String, Double> detailed = scorer.calculateDetailedPenalties(source, candidate);

            assertThat(penalty).isEqualTo(0.0);
            assertThat(detailed).isEmpty();
        }

        @Test
        @DisplayName("should not penalize official media annotations (Official Video, Audio, Visualizer)")
        void shouldNotPenalizeOfficialMediaAnnotations() {
            CandidateTrackFeatures candidate = extractCandidate("Daft Punk - Get Lucky (Official Video)", "Daft Punk");
            SourceTrackFeatures source = studioSource("Get Lucky", "Daft Punk");

            Double penalty = scorer.calculatePenalty(source, candidate);
            Map<String, Double> detailed = scorer.calculateDetailedPenalties(source, candidate);

            assertThat(penalty).isEqualTo(0.0);
            assertThat(detailed).isEmpty();
        }
    }

    @Nested
    @DisplayName("End-to-End Extraction and Scoring Integration")
    class EndToEndExtractionAndScoringIntegration {

        @Test
        @DisplayName("should penalize extracted cover version")
        void shouldPenalizeExtractedCover() {
            CandidateTrackFeatures candidate = extractCandidate("Get Lucky (Cover by XYZ)", "Some Channel");
            SourceTrackFeatures source = studioSource("Get Lucky", "Daft Punk");

            Double penalty = scorer.calculatePenalty(source, candidate);
            Map<String, Double> detailed = scorer.calculateDetailedPenalties(source, candidate);

            assertThat(penalty).isEqualTo(0.25);
            assertThat(detailed).containsEntry("COVER", 0.25);
        }

        @Test
        @DisplayName("should penalize extracted reaction video")
        void shouldPenalizeExtractedReaction() {
            CandidateTrackFeatures candidate = extractCandidate("Daft Punk - Get Lucky (Reaction Video)", "Reactors");
            SourceTrackFeatures source = studioSource("Get Lucky", "Daft Punk");

            Double penalty = scorer.calculatePenalty(source, candidate);
            Map<String, Double> detailed = scorer.calculateDetailedPenalties(source, candidate);

            assertThat(penalty).isEqualTo(0.30);
            assertThat(detailed).containsEntry("REACTION", 0.30);
        }

        @Test
        @DisplayName("should penalize extracted karaoke track")
        void shouldPenalizeExtractedKaraoke() {
            CandidateTrackFeatures candidate = extractCandidate("Get Lucky - Karaoke Version", "Karaoke Channel");
            SourceTrackFeatures source = studioSource("Get Lucky", "Daft Punk");

            Double penalty = scorer.calculatePenalty(source, candidate);
            Map<String, Double> detailed = scorer.calculateDetailedPenalties(source, candidate);

            assertThat(penalty).isEqualTo(0.30);
            assertThat(detailed).containsEntry("KARAOKE", 0.30);
        }

        @Test
        @DisplayName("should penalize extracted nightcore track")
        void shouldPenalizeExtractedNightcore() {
            CandidateTrackFeatures candidate = extractCandidate("Nightcore - Get Lucky", "Nightcore Channel");
            SourceTrackFeatures source = studioSource("Get Lucky", "Daft Punk");

            Double penalty = scorer.calculatePenalty(source, candidate);
            Map<String, Double> detailed = scorer.calculateDetailedPenalties(source, candidate);

            assertThat(penalty).isEqualTo(0.20);
            assertThat(detailed).containsEntry("NIGHTCORE", 0.20);
        }

        @Test
        @DisplayName("should penalize extracted sped up track")
        void shouldPenalizeExtractedSpedUp() {
            CandidateTrackFeatures candidate = extractCandidate("Get Lucky (Sped Up Version)", "Speed Audios");
            SourceTrackFeatures source = studioSource("Get Lucky", "Daft Punk");

            Double penalty = scorer.calculatePenalty(source, candidate);
            Map<String, Double> detailed = scorer.calculateDetailedPenalties(source, candidate);

            assertThat(penalty).isEqualTo(0.15);
            assertThat(detailed).containsEntry("SPED_UP", 0.15);
        }

        @Test
        @DisplayName("should penalize extracted slowed reverb track")
        void shouldPenalizeExtractedSlowed() {
            CandidateTrackFeatures candidate = extractCandidate("Get Lucky (Slowed + Reverb)", "Vibes");
            SourceTrackFeatures source = studioSource("Get Lucky", "Daft Punk");

            Double penalty = scorer.calculatePenalty(source, candidate);
            Map<String, Double> detailed = scorer.calculateDetailedPenalties(source, candidate);

            assertThat(penalty).isEqualTo(0.15);
            assertThat(detailed).containsEntry("SLOWED", 0.15);
        }

        @Test
        @DisplayName("should penalize extracted live performance when source is studio")
        void shouldPenalizeExtractedLive() {
            CandidateTrackFeatures candidate = extractCandidate("Daft Punk - Get Lucky (Live at Grammys)", "Grammys");
            SourceTrackFeatures source = studioSource("Get Lucky", "Daft Punk");

            Double penalty = scorer.calculatePenalty(source, candidate);
            Map<String, Double> detailed = scorer.calculateDetailedPenalties(source, candidate);

            assertThat(penalty).isEqualTo(0.15);
            assertThat(detailed).containsEntry("LIVE_MISMATCH", 0.15);
        }
    }

    @Nested
    @DisplayName("Null, Blank, and Missing Data Safety")
    class NullBlankAndMissingDataSafety {

        @Test
        @DisplayName("should throw NullPointerException when source track is null")
        void shouldThrowNpeOnNullSource() {
            CandidateTrackFeatures candidate = candidateWithIndicators("Title", Set.of());
            assertThatThrownBy(() -> scorer.calculatePenalty(null, candidate))
                    .isInstanceOf(NullPointerException.class)
                    .hasMessageContaining("SourceTrackFeatures");
            assertThatThrownBy(() -> scorer.calculateDetailedPenalties(null, candidate))
                    .isInstanceOf(NullPointerException.class)
                    .hasMessageContaining("SourceTrackFeatures");
        }

        @Test
        @DisplayName("should throw NullPointerException when candidate track is null")
        void shouldThrowNpeOnNullCandidate() {
            SourceTrackFeatures source = studioSource("Title", "Artist");
            assertThatThrownBy(() -> scorer.calculatePenalty(source, null))
                    .isInstanceOf(NullPointerException.class)
                    .hasMessageContaining("CandidateTrackFeatures");
            assertThatThrownBy(() -> scorer.calculateDetailedPenalties(source, null))
                    .isInstanceOf(NullPointerException.class)
                    .hasMessageContaining("CandidateTrackFeatures");
        }

        @Test
        @DisplayName("should handle null indicators in overloads safely as empty sets")
        void shouldHandleNullIndicatorsInOverloadsSafely() {
            Double penalty = scorer.calculatePenalty((Set<VersionIndicator>) null, null);
            Map<String, Double> detailed = scorer.calculateDetailedPenalties((Set<VersionIndicator>) null, null);

            assertThat(penalty).isEqualTo(0.0);
            assertThat(detailed).isEmpty();
        }

        @Test
        @DisplayName("score default method delegates to calculatePenalty")
        void scoreDefaultMethodDelegatesToCalculatePenalty() {
            SourceTrackFeatures source = studioSource("Get Lucky", "Daft Punk");
            CandidateTrackFeatures candidate = candidateWithIndicators("Get Lucky (Cover)", Set.of(VersionIndicator.COVER));

            Double scoreResult = scorer.score(source, candidate);
            Double calcResult = scorer.calculatePenalty(source, candidate);

            assertThat(scoreResult).isEqualTo(calcResult).isEqualTo(0.25);
        }
    }

    @Nested
    @DisplayName("Immutability and Determinism")
    class ImmutabilityAndDeterminism {

        @Test
        @DisplayName("calculateDetailedPenalties map should be unmodifiable")
        void detailedPenaltiesMapShouldBeUnmodifiable() {
            SourceTrackFeatures source = studioSource("Get Lucky", "Daft Punk");
            CandidateTrackFeatures candidate = candidateWithIndicators("Get Lucky (Cover)", Set.of(VersionIndicator.COVER));

            Map<String, Double> detailed = scorer.calculateDetailedPenalties(source, candidate);

            assertThatThrownBy(() -> detailed.put("NEW_PENALTY", 0.50))
                    .isInstanceOf(UnsupportedOperationException.class);
        }

        @Test
        @DisplayName("should produce identical deterministic output across 100 repeated executions")
        void shouldBeDeterministicAcrossRepeatedExecutions() {
            SourceTrackFeatures source = studioSource("Get Lucky", "Daft Punk");
            CandidateTrackFeatures candidate = candidateWithIndicators(
                    "Get Lucky (Cover + Live)",
                    Set.of(VersionIndicator.COVER, VersionIndicator.LIVE)
            );

            Double baselinePenalty = scorer.calculatePenalty(source, candidate);
            Map<String, Double> baselineDetailed = scorer.calculateDetailedPenalties(source, candidate);

            for (int i = 0; i < 100; i++) {
                assertThat(scorer.calculatePenalty(source, candidate)).isEqualTo(baselinePenalty);
                assertThat(scorer.calculateDetailedPenalties(source, candidate)).isEqualTo(baselineDetailed);
            }
        }
    }
}
