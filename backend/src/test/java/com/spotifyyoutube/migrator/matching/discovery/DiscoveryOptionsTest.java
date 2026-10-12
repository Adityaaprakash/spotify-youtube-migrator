package com.spotifyyoutube.migrator.matching.discovery;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DiscoveryOptionsTest {

    @Test
    @DisplayName("Defaults factory returns sensible defaults and all strategies enabled")
    void defaultsFactoryProvidesStandardConfiguration() {
        DiscoveryOptions options = DiscoveryOptions.defaults();

        assertThat(options.maxCandidates()).isEqualTo(DiscoveryOptions.DEFAULT_MAX_CANDIDATES);
        assertThat(options.maxQueries()).isEqualTo(DiscoveryOptions.DEFAULT_MAX_QUERIES);
        assertThat(options.resultsPerQuery()).isEqualTo(DiscoveryOptions.DEFAULT_RESULTS_PER_QUERY);
        assertThat(options.enabledStrategies()).containsExactlyInAnyOrder(SearchQueryStrategy.values());
    }

    @Test
    @DisplayName("Creates custom options with specific allowed strategies")
    void createsCustomOptionsWithSelectedStrategies() {
        Set<SearchQueryStrategy> strategies = Set.of(
                SearchQueryStrategy.PRIMARY_TITLE_ARTIST,
                SearchQueryStrategy.TITLE_ARTIST_FEATURED
        );
        DiscoveryOptions options = new DiscoveryOptions(15, 3, 12, strategies);

        assertThat(options.maxCandidates()).isEqualTo(15);
        assertThat(options.maxQueries()).isEqualTo(3);
        assertThat(options.resultsPerQuery()).isEqualTo(12);
        assertThat(options.enabledStrategies()).isEqualTo(strategies);
    }

    @Test
    @DisplayName("Null or empty enabledStrategies defaults to all strategies")
    void nullOrEmptyStrategiesDefaultsToAll() {
        DiscoveryOptions nullStrategies = new DiscoveryOptions(20, 2, 10, null);
        DiscoveryOptions emptyStrategies = new DiscoveryOptions(20, 2, 10, Set.of());

        assertThat(nullStrategies.enabledStrategies()).containsExactlyInAnyOrder(SearchQueryStrategy.values());
        assertThat(emptyStrategies.enabledStrategies()).containsExactlyInAnyOrder(SearchQueryStrategy.values());
    }

    @Test
    @DisplayName("Defensively copies enabledStrategies so external set mutations do not affect options")
    void defensiveCopyOfStrategies() {
        Set<SearchQueryStrategy> strategies = Set.of(SearchQueryStrategy.PRIMARY_TITLE_ARTIST);
        DiscoveryOptions options = new DiscoveryOptions(20, 2, 10, strategies);

        assertThatThrownBy(() -> options.enabledStrategies().add(SearchQueryStrategy.CLEAN_TITLE_ONLY))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @ParameterizedTest
    @ValueSource(ints = {0, -1, 51, 100})
    @DisplayName("Throws IllegalArgumentException when maxCandidates is out of bounds")
    void throwsOnInvalidMaxCandidates(int invalidLimit) {
        assertThatThrownBy(() -> new DiscoveryOptions(invalidLimit, 2, 10, Set.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("maxCandidates must be between 1 and 50");
    }

    @ParameterizedTest
    @ValueSource(ints = {0, -1, 6, 10})
    @DisplayName("Throws IllegalArgumentException when maxQueries is out of bounds")
    void throwsOnInvalidMaxQueries(int invalidQueries) {
        assertThatThrownBy(() -> new DiscoveryOptions(20, invalidQueries, 10, Set.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("maxQueries must be between 1 and 5");
    }

    @ParameterizedTest
    @ValueSource(ints = {0, -1, 51, 100})
    @DisplayName("Throws IllegalArgumentException when resultsPerQuery is out of bounds")
    void throwsOnInvalidResultsPerQuery(int invalidResults) {
        assertThatThrownBy(() -> new DiscoveryOptions(20, 2, invalidResults, Set.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("resultsPerQuery must be between 1 and 50");
    }
}
