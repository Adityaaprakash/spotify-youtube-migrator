package com.spotifyyoutube.migrator.matching.discovery;

import com.spotifyyoutube.migrator.matching.normalization.AlbumNormalizer;
import com.spotifyyoutube.migrator.matching.normalization.ArtistNormalizer;
import com.spotifyyoutube.migrator.matching.normalization.DurationNormalizer;
import com.spotifyyoutube.migrator.matching.normalization.LivePerformanceDetails;
import com.spotifyyoutube.migrator.matching.normalization.MetadataToken;
import com.spotifyyoutube.migrator.matching.normalization.MetadataTokenizer;
import com.spotifyyoutube.migrator.matching.normalization.NormalizationOrchestrator;
import com.spotifyyoutube.migrator.matching.normalization.NormalizedArtistCredit;
import com.spotifyyoutube.migrator.matching.normalization.NormalizedTrackMetadata;
import com.spotifyyoutube.migrator.matching.normalization.RawTrackMetadata;
import com.spotifyyoutube.migrator.matching.normalization.RemasterDetails;
import com.spotifyyoutube.migrator.matching.normalization.TitleNormalizer;
import com.spotifyyoutube.migrator.matching.normalization.TokenCategory;
import com.spotifyyoutube.migrator.matching.normalization.TokenizedTitle;
import com.spotifyyoutube.migrator.matching.normalization.VariantType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Duration;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("DefaultSearchQueryGenerator Tests")
class DefaultSearchQueryGeneratorTest {

    private DefaultSearchQueryGenerator generator;
    private NormalizationOrchestrator orchestrator;

    @BeforeEach
    void setUp() {
        generator = new DefaultSearchQueryGenerator();
        orchestrator = new NormalizationOrchestrator(
                new TitleNormalizer(),
                new ArtistNormalizer(),
                new AlbumNormalizer(),
                new DurationNormalizer(),
                new MetadataTokenizer()
        );
    }

    @Nested
    @DisplayName("1. Ordinary Title and Artist")
    class OrdinaryTitleAndArtistTests {

        @Test
        @DisplayName("Generates primary artist and clean title for ordinary track")
        void generatesPrimaryArtistAndCleanTitle() {
            RawTrackMetadata raw = RawTrackMetadata.of("Get Lucky", "Daft Punk", null, 240000L);
            NormalizedTrackMetadata metadata = orchestrator.normalize(raw);

            List<SearchQuery> queries = generator.generateQueries(metadata, DiscoveryOptions.defaults());

            assertThat(queries).hasSize(1);
            SearchQuery primaryQuery = queries.get(0);
            assertThat(primaryQuery.query()).isEqualTo("daft punk - get lucky");
            assertThat(primaryQuery.strategy()).isEqualTo(SearchQueryStrategy.PRIMARY_TITLE_ARTIST);
            assertThat(primaryQuery.priority()).isEqualTo(1);
        }

        @Test
        @DisplayName("One-argument generateQueries uses default options")
        void oneArgumentUsesDefaultOptions() {
            RawTrackMetadata raw = RawTrackMetadata.of("Hello", "Adele", null, 255000L);
            NormalizedTrackMetadata metadata = orchestrator.normalize(raw);

            List<SearchQuery> queries = generator.generateQueries(metadata);

            assertThat(queries).hasSize(1);
            assertThat(queries.get(0).query()).isEqualTo("adele - hello");
            assertThat(queries.get(0).strategy()).isEqualTo(SearchQueryStrategy.PRIMARY_TITLE_ARTIST);
        }
    }

    @Nested
    @DisplayName("2. Featured Artists")
    class FeaturedArtistsTests {

        @Test
        @DisplayName("Generates primary and featured queries when featured artists present")
        void generatesFeaturedArtistQuery() {
            RawTrackMetadata raw = RawTrackMetadata.of("Levitating (feat. DaBaby)", "Dua Lipa", null, 203000L);
            NormalizedTrackMetadata metadata = orchestrator.normalize(raw);

            List<SearchQuery> queries = generator.generateQueries(metadata, DiscoveryOptions.defaults());

            assertThat(queries).hasSize(2);
            assertThat(queries.get(0).strategy()).isEqualTo(SearchQueryStrategy.PRIMARY_TITLE_ARTIST);
            assertThat(queries.get(0).query()).isEqualTo("dua lipa - levitating");
            assertThat(queries.get(0).priority()).isEqualTo(1);

            assertThat(queries.get(1).strategy()).isEqualTo(SearchQueryStrategy.TITLE_ARTIST_FEATURED);
            assertThat(queries.get(1).query()).isEqualTo("dua lipa feat. dababy - levitating");
            assertThat(queries.get(1).priority()).isEqualTo(2);
        }

        @Test
        @DisplayName("Joins multiple featured artists cleanly")
        void joinsMultipleFeaturedArtists() {
            NormalizedTrackMetadata metadata = new NormalizedTrackMetadata(
                    RawTrackMetadata.of("Song", "Artist", null, 180000L),
                    "song",
                    new NormalizedArtistCredit("artist", List.of("feat 1", "feat 2")),
                    List.of("artist"),
                    List.of("feat 1", "feat 2"),
                    "",
                    Duration.ofSeconds(180),
                    new TokenizedTitle("Song", "song", false, Set.of(), null, null, Set.of(), List.of(), List.of(), List.of())
            );

            List<SearchQuery> queries = generator.generateQueries(metadata, DiscoveryOptions.defaults());

            assertThat(queries).hasSize(2);
            assertThat(queries.get(1).strategy()).isEqualTo(SearchQueryStrategy.TITLE_ARTIST_FEATURED);
            assertThat(queries.get(1).query()).isEqualTo("artist feat. feat 1, feat 2 - song");
        }
    }

    @Nested
    @DisplayName("3. Album-Qualified Queries")
    class AlbumQualifiedTests {

        @Test
        @DisplayName("Generates album-qualified query when album is present")
        void generatesAlbumQualifiedQuery() {
            RawTrackMetadata raw = RawTrackMetadata.of("Get Lucky", "Daft Punk", "Random Access Memories", 248000L);
            NormalizedTrackMetadata metadata = orchestrator.normalize(raw);

            List<SearchQuery> queries = generator.generateQueries(metadata, DiscoveryOptions.defaults());

            assertThat(queries).hasSize(2);
            assertThat(queries.get(0).strategy()).isEqualTo(SearchQueryStrategy.PRIMARY_TITLE_ARTIST);
            assertThat(queries.get(0).query()).isEqualTo("daft punk - get lucky");

            assertThat(queries.get(1).strategy()).isEqualTo(SearchQueryStrategy.TITLE_ARTIST_ALBUM);
            assertThat(queries.get(1).query()).isEqualTo("daft punk - get lucky random access memories");
            assertThat(queries.get(1).priority()).isEqualTo(2);
        }

        @Test
        @DisplayName("Omits album query when album is identical to title")
        void omitsAlbumQueryWhenIdenticalToTitle() {
            RawTrackMetadata raw = RawTrackMetadata.of("Iron Maiden", "Iron Maiden", "Iron Maiden", 215000L);
            NormalizedTrackMetadata metadata = orchestrator.normalize(raw);

            List<SearchQuery> queries = generator.generateQueries(metadata, DiscoveryOptions.defaults());

            assertThat(queries).hasSize(1);
            assertThat(queries.get(0).strategy()).isEqualTo(SearchQueryStrategy.PRIMARY_TITLE_ARTIST);
            assertThat(queries.get(0).query()).isEqualTo("iron maiden - iron maiden");
        }
    }

    @Nested
    @DisplayName("4. Meaningful Version Tokens")
    class VersionTokensTests {

        @Test
        @DisplayName("Generates version-qualified query for live performance")
        void generatesVersionQueryForLiveTrack() {
            RawTrackMetadata raw = RawTrackMetadata.of("Hotel California (Live at Wembley)", "Eagles", null, 390000L);
            NormalizedTrackMetadata metadata = orchestrator.normalize(raw);

            DiscoveryOptions options = new DiscoveryOptions(20, 3, 10, null);
            List<SearchQuery> queries = generator.generateQueries(metadata, options);

            assertThat(queries).hasSize(2);
            assertThat(queries.get(0).strategy()).isEqualTo(SearchQueryStrategy.PRIMARY_TITLE_ARTIST);
            assertThat(queries.get(0).query()).isEqualTo("eagles - hotel california");

            assertThat(queries.get(1).strategy()).isEqualTo(SearchQueryStrategy.TITLE_ARTIST_VERSION);
            assertThat(queries.get(1).query()).isEqualTo("eagles - hotel california live at wembley");
            assertThat(queries.get(1).priority()).isEqualTo(2);
        }

        @Test
        @DisplayName("Generates version query for remaster with year")
        void generatesVersionQueryForRemaster() {
            RawTrackMetadata raw = RawTrackMetadata.of("In the Air Tonight (2015 Remaster)", "Phil Collins", null, 330000L);
            NormalizedTrackMetadata metadata = orchestrator.normalize(raw);

            DiscoveryOptions options = new DiscoveryOptions(20, 3, 10, null);
            List<SearchQuery> queries = generator.generateQueries(metadata, options);

            assertThat(queries).hasSize(2);
            assertThat(queries.get(0).strategy()).isEqualTo(SearchQueryStrategy.PRIMARY_TITLE_ARTIST);
            assertThat(queries.get(1).strategy()).isEqualTo(SearchQueryStrategy.TITLE_ARTIST_VERSION);
            assertThat(queries.get(1).query()).isEqualTo("phil collins - in the air tonight 2015 remaster");
        }

        @Test
        @DisplayName("Generates version query from fallback VariantType when tokens are absent")
        void generatesVersionQueryFromVariantsFallback() {
            NormalizedTrackMetadata metadata = new NormalizedTrackMetadata(
                    RawTrackMetadata.of("Strobe", "Deadmau5", null, 630000L),
                    "strobe",
                    new NormalizedArtistCredit("deadmau5", List.of()),
                    List.of("deadmau5"),
                    List.of(),
                    "",
                    Duration.ofSeconds(630),
                    new TokenizedTitle("Strobe", "strobe", false, Set.of(), null, null, Set.of(VariantType.EXTENDED_MIX), List.of(), List.of(), List.of())
            );

            DiscoveryOptions options = new DiscoveryOptions(20, 2, 10, null);
            List<SearchQuery> queries = generator.generateQueries(metadata, options);

            assertThat(queries).hasSize(2);
            assertThat(queries.get(1).strategy()).isEqualTo(SearchQueryStrategy.TITLE_ARTIST_VERSION);
            assertThat(queries.get(1).query()).isEqualTo("deadmau5 - strobe extended mix");
        }
    }

    @Nested
    @DisplayName("5. Missing or Blank Metadata & Fallbacks")
    class MissingOrBlankMetadataTests {

        @Test
        @DisplayName("Generates clean title only when artist is absent")
        void generatesCleanTitleOnlyWhenArtistAbsent() {
            RawTrackMetadata raw = RawTrackMetadata.of("Interstellar Main Theme", "", null, 240000L);
            NormalizedTrackMetadata metadata = orchestrator.normalize(raw);

            List<SearchQuery> queries = generator.generateQueries(metadata, DiscoveryOptions.defaults());

            assertThat(queries).hasSize(1);
            assertThat(queries.get(0).strategy()).isEqualTo(SearchQueryStrategy.CLEAN_TITLE_ONLY);
            assertThat(queries.get(0).query()).isEqualTo("interstellar main theme");
            assertThat(queries.get(0).priority()).isEqualTo(1);
        }

        @Test
        @DisplayName("Generates clean title only for various artists soundtrack credit")
        void generatesCleanTitleOnlyForVariousArtists() {
            RawTrackMetadata raw = RawTrackMetadata.of("City of Stars", "Various Artists", null, 150000L);
            NormalizedTrackMetadata metadata = orchestrator.normalize(raw);

            DiscoveryOptions options = new DiscoveryOptions(20, 3, 10, null);
            List<SearchQuery> queries = generator.generateQueries(metadata, options);

            assertThat(queries).hasSize(2);
            assertThat(queries.get(0).strategy()).isEqualTo(SearchQueryStrategy.PRIMARY_TITLE_ARTIST);
            assertThat(queries.get(1).strategy()).isEqualTo(SearchQueryStrategy.CLEAN_TITLE_ONLY);
            assertThat(queries.get(1).query()).isEqualTo("city of stars");
        }

        @Test
        @DisplayName("Returns empty list when both title and artist are blank")
        void returnsEmptyListWhenTitleAndArtistBlank() {
            RawTrackMetadata raw = RawTrackMetadata.of("", "", null, 0L);
            NormalizedTrackMetadata metadata = orchestrator.normalize(raw);

            List<SearchQuery> queries = generator.generateQueries(metadata, DiscoveryOptions.defaults());

            assertThat(queries).isEmpty();
        }

        @Test
        @DisplayName("Throws NullPointerException when trackMetadata is null")
        void throwsOnNullTrackMetadata() {
            assertThatThrownBy(() -> generator.generateQueries(null, DiscoveryOptions.defaults()))
                    .isInstanceOf(NullPointerException.class)
                    .hasMessageContaining("trackMetadata must not be null");
        }

        @Test
        @DisplayName("Throws NullPointerException when options is null")
        void throwsOnNullOptions() {
            RawTrackMetadata raw = RawTrackMetadata.of("Track", "Artist", null, 1000L);
            NormalizedTrackMetadata metadata = orchestrator.normalize(raw);

            assertThatThrownBy(() -> generator.generateQueries(metadata, null))
                    .isInstanceOf(NullPointerException.class)
                    .hasMessageContaining("options must not be null");
        }
    }

    @Nested
    @DisplayName("6. Unicode and Punctuation Safety")
    class UnicodeAndPunctuationTests {

        @Test
        @DisplayName("Safely preserves non-Latin scripts")
        void safelyPreservesNonLatinScripts() {
            RawTrackMetadata raw = RawTrackMetadata.of("夜に駆ける", "YOASOBI", null, 260000L);
            NormalizedTrackMetadata metadata = orchestrator.normalize(raw);

            List<SearchQuery> queries = generator.generateQueries(metadata, DiscoveryOptions.defaults());

            assertThat(queries).hasSize(1);
            assertThat(queries.get(0).query()).isEqualTo("yoasobi - 夜に駆ける");
        }

        @Test
        @DisplayName("Safely handles artist names with punctuation and ampersands")
        void safelyHandlesPunctuationAndAmpersands() {
            RawTrackMetadata raw = RawTrackMetadata.of("Highway to Hell", "AC/DC", null, 208000L);
            NormalizedTrackMetadata metadata = orchestrator.normalize(raw);

            List<SearchQuery> queries = generator.generateQueries(metadata, DiscoveryOptions.defaults());

            assertThat(queries).hasSize(1);
            assertThat(queries.get(0).query()).isEqualTo("ac/dc - highway to hell");
        }
    }

    @Nested
    @DisplayName("7. Duplicate Suppression & Raw Fallback")
    class DuplicateSuppressionTests {

        @Test
        @DisplayName("Suppresses case-insensitive and whitespace duplicate queries")
        void suppressesDuplicateQueries() {
            // Raw title and artist are identical to clean title and artist
            RawTrackMetadata raw = RawTrackMetadata.of("Get Lucky", "Daft Punk", null, 240000L);
            NormalizedTrackMetadata metadata = orchestrator.normalize(raw);

            DiscoveryOptions options = new DiscoveryOptions(20, 5, 10, Set.of(
                    SearchQueryStrategy.PRIMARY_TITLE_ARTIST,
                    SearchQueryStrategy.RAW_TITLE_AND_ARTIST
            ));

            List<SearchQuery> queries = generator.generateQueries(metadata, options);

            // Raw query "Daft Punk - Get Lucky" matches "daft punk - get lucky" case-insensitively, so it is suppressed
            assertThat(queries).hasSize(1);
            assertThat(queries.get(0).strategy()).isEqualTo(SearchQueryStrategy.PRIMARY_TITLE_ARTIST);
        }

        @Test
        @DisplayName("Retains raw query when raw title contains substantive unstripped promotional markers")
        void retainsRawQueryWhenSubstantivelyDifferent() {
            RawTrackMetadata raw = RawTrackMetadata.of("Blinding Lights (Official Video)", "The Weeknd", null, 200000L);
            NormalizedTrackMetadata metadata = orchestrator.normalize(raw);

            DiscoveryOptions options = new DiscoveryOptions(20, 2, 10, Set.of(
                    SearchQueryStrategy.PRIMARY_TITLE_ARTIST,
                    SearchQueryStrategy.RAW_TITLE_AND_ARTIST
            ));

            List<SearchQuery> queries = generator.generateQueries(metadata, options);

            assertThat(queries).hasSize(2);
            assertThat(queries.get(0).strategy()).isEqualTo(SearchQueryStrategy.PRIMARY_TITLE_ARTIST);
            assertThat(queries.get(0).query()).isEqualTo("the weeknd - blinding lights");

            assertThat(queries.get(1).strategy()).isEqualTo(SearchQueryStrategy.RAW_TITLE_AND_ARTIST);
            assertThat(queries.get(1).query()).isEqualTo("The Weeknd - Blinding Lights (Official Video)");
            assertThat(queries.get(1).priority()).isEqualTo(2);
        }
    }

    @Nested
    @DisplayName("8. Disabled Strategies")
    class DisabledStrategiesTests {

        @Test
        @DisplayName("Only generates queries for explicitly enabled strategies")
        void respectsEnabledStrategies() {
            RawTrackMetadata raw = RawTrackMetadata.of("Levitating (feat. DaBaby)", "Dua Lipa", "Future Nostalgia", 203000L);
            NormalizedTrackMetadata metadata = orchestrator.normalize(raw);

            DiscoveryOptions options = new DiscoveryOptions(20, 5, 10, Set.of(
                    SearchQueryStrategy.TITLE_ARTIST_ALBUM
            ));

            List<SearchQuery> queries = generator.generateQueries(metadata, options);

            assertThat(queries).hasSize(1);
            assertThat(queries.get(0).strategy()).isEqualTo(SearchQueryStrategy.TITLE_ARTIST_ALBUM);
            assertThat(queries.get(0).query()).isEqualTo("dua lipa - levitating future nostalgia");
            assertThat(queries.get(0).priority()).isEqualTo(1);
        }

        @Test
        @DisplayName("Generates clean title only when it is the sole enabled strategy")
        void generatesCleanTitleOnlyWhenSoleEnabledStrategy() {
            RawTrackMetadata raw = RawTrackMetadata.of("Hello", "Adele", null, 255000L);
            NormalizedTrackMetadata metadata = orchestrator.normalize(raw);

            DiscoveryOptions options = new DiscoveryOptions(20, 2, 10, Set.of(
                    SearchQueryStrategy.CLEAN_TITLE_ONLY
            ));

            List<SearchQuery> queries = generator.generateQueries(metadata, options);

            assertThat(queries).hasSize(1);
            assertThat(queries.get(0).strategy()).isEqualTo(SearchQueryStrategy.CLEAN_TITLE_ONLY);
            assertThat(queries.get(0).query()).isEqualTo("hello");
            assertThat(queries.get(0).priority()).isEqualTo(1);
        }
    }

    @Nested
    @DisplayName("9. MaxQueries Boundaries & Quota Limits")
    class MaxQueriesBoundariesTests {

        @ParameterizedTest
        @ValueSource(ints = {1, 2, 3, 4, 5})
        @DisplayName("Strictly bounds query count to maxQueries")
        void strictlyBoundsToMaxQueries(int maxQueries) {
            // Rich track with featured artists, album, and version
            RawTrackMetadata raw = RawTrackMetadata.of("Hotel California (Live at Wembley)", "Eagles", "Hell Freezes Over", 400000L);
            NormalizedTrackMetadata metadata = orchestrator.normalize(raw);

            DiscoveryOptions options = new DiscoveryOptions(20, maxQueries, 10, null);
            List<SearchQuery> queries = generator.generateQueries(metadata, options);

            assertThat(queries.size()).isLessThanOrEqualTo(maxQueries);
            if (maxQueries == 1) {
                assertThat(queries).hasSize(1);
                assertThat(queries.get(0).priority()).isEqualTo(1);
            }
        }

        @Test
        @DisplayName("Returns fewer queries when metadata cannot support more variants without padding")
        void doesNotPadQueriesToReachMax() {
            RawTrackMetadata raw = RawTrackMetadata.of("Song", "Artist", null, 180000L);
            NormalizedTrackMetadata metadata = orchestrator.normalize(raw);

            DiscoveryOptions options = new DiscoveryOptions(20, 5, 10, null);
            List<SearchQuery> queries = generator.generateQueries(metadata, options);

            // Plain track without featured, album, version, or distinct raw title produces only 1 query
            assertThat(queries).hasSize(1);
            assertThat(queries.get(0).strategy()).isEqualTo(SearchQueryStrategy.PRIMARY_TITLE_ARTIST);
        }
    }

    @Nested
    @DisplayName("10. Maximum Query Length")
    class MaxQueryLengthTests {

        @Test
        @DisplayName("Safely bounds query string at 500 characters without throwing exception")
        void safelyBoundsQueryAtMaxLength() {
            String extremelyLongTitle = "a".repeat(450);
            String extremelyLongArtist = "b".repeat(100);

            RawTrackMetadata raw = RawTrackMetadata.of(extremelyLongTitle, extremelyLongArtist, null, 1000L);
            NormalizedTrackMetadata metadata = orchestrator.normalize(raw);

            List<SearchQuery> queries = generator.generateQueries(metadata, DiscoveryOptions.defaults());

            assertThat(queries).hasSize(1);
            assertThat(queries.get(0).query().length()).isLessThanOrEqualTo(SearchQuery.MAX_QUERY_LENGTH);
            assertThat(queries.get(0).query().length()).isEqualTo(SearchQuery.MAX_QUERY_LENGTH);
        }
    }

    @Nested
    @DisplayName("11. Determinism and Priority Stability")
    class DeterminismAndStabilityTests {

        @Test
        @DisplayName("Produces identical queries, strategies, and priorities across repeated calls")
        void producesIdenticalQueriesAcrossRepeatedCalls() {
            RawTrackMetadata raw = RawTrackMetadata.of("Levitating (feat. DaBaby)", "Dua Lipa", "Future Nostalgia", 203000L);
            NormalizedTrackMetadata metadata = orchestrator.normalize(raw);
            DiscoveryOptions options = new DiscoveryOptions(20, 3, 10, null);

            List<SearchQuery> run1 = generator.generateQueries(metadata, options);
            List<SearchQuery> run2 = generator.generateQueries(metadata, options);

            assertThat(run1).isEqualTo(run2);
            for (int i = 0; i < run1.size(); i++) {
                assertThat(run1.get(i).priority()).isEqualTo(i + 1);
            }
        }
    }

    @Nested
    @DisplayName("12. No Fabricated Metadata")
    class NoFabricatedMetadataTests {

        @Test
        @DisplayName("Never fabricates album or featured artist when absent")
        void neverFabricatesAlbumOrFeaturedArtist() {
            RawTrackMetadata raw = RawTrackMetadata.of("Clean Song", "Artist Only", null, 180000L);
            NormalizedTrackMetadata metadata = orchestrator.normalize(raw);

            DiscoveryOptions options = new DiscoveryOptions(20, 5, 10, null);
            List<SearchQuery> queries = generator.generateQueries(metadata, options);

            assertThat(queries).noneMatch(q -> q.strategy() == SearchQueryStrategy.TITLE_ARTIST_FEATURED);
            assertThat(queries).noneMatch(q -> q.strategy() == SearchQueryStrategy.TITLE_ARTIST_ALBUM);
            assertThat(queries).noneMatch(q -> q.strategy() == SearchQueryStrategy.TITLE_ARTIST_VERSION);
        }
    }

    @Nested
    @DisplayName("13. Output Hygiene & Immutability")
    class HygieneAndImmutabilityTests {

        @Test
        @DisplayName("Returned list is unmodifiable")
        void returnedListIsUnmodifiable() {
            RawTrackMetadata raw = RawTrackMetadata.of("Track", "Artist", null, 1000L);
            NormalizedTrackMetadata metadata = orchestrator.normalize(raw);

            List<SearchQuery> queries = generator.generateQueries(metadata, DiscoveryOptions.defaults());

            assertThatThrownBy(() -> queries.add(new SearchQuery("extra", SearchQueryStrategy.CLEAN_TITLE_ONLY)))
                    .isInstanceOf(UnsupportedOperationException.class);
        }

        @Test
        @DisplayName("Never produces blank query string")
        void neverProducesBlankQueryString() {
            RawTrackMetadata raw = RawTrackMetadata.of("   ", "   ", "   ", 0L);
            NormalizedTrackMetadata metadata = orchestrator.normalize(raw);

            List<SearchQuery> queries = generator.generateQueries(metadata, DiscoveryOptions.defaults());

            assertThat(queries).isEmpty();
        }
    }
}
