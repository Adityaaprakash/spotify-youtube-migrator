package com.spotifyyoutube.migrator.matching.discovery;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SearchQueryTest {

    @Test
    @DisplayName("Creates search query with valid parameters and trims query string")
    void createsSearchQueryWithValidParameters() {
        SearchQuery query = new SearchQuery("  Daft Punk - Get Lucky  ", SearchQueryStrategy.PRIMARY_TITLE_ARTIST, 2);

        assertThat(query.query()).isEqualTo("Daft Punk - Get Lucky");
        assertThat(query.strategy()).isEqualTo(SearchQueryStrategy.PRIMARY_TITLE_ARTIST);
        assertThat(query.priority()).isEqualTo(2);
    }

    @Test
    @DisplayName("Two-argument constructor defaults priority to 1")
    void twoArgumentConstructorDefaultsPriorityToOne() {
        SearchQuery query = new SearchQuery("Daft Punk - Get Lucky", SearchQueryStrategy.PRIMARY_TITLE_ARTIST);

        assertThat(query.priority()).isEqualTo(1);
    }

    @Test
    @DisplayName("Accepts query string at maximum length boundary of 500 characters")
    void acceptsQueryAtMaxLengthBoundary() {
        String longQuery = "a".repeat(500);
        SearchQuery query = new SearchQuery(longQuery, SearchQueryStrategy.PRIMARY_TITLE_ARTIST);

        assertThat(query.query()).hasSize(500);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"   ", "\t\n  "})
    @DisplayName("Throws IllegalArgumentException when query is null or blank")
    void throwsOnBlankQuery(String invalidQuery) {
        assertThatThrownBy(() -> new SearchQuery(invalidQuery, SearchQueryStrategy.PRIMARY_TITLE_ARTIST))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Search query must not be blank");
    }

    @Test
    @DisplayName("Throws IllegalArgumentException when query exceeds 500 characters")
    void throwsOnQueryExceedingMaxLength() {
        String tooLongQuery = "a".repeat(501);

        assertThatThrownBy(() -> new SearchQuery(tooLongQuery, SearchQueryStrategy.PRIMARY_TITLE_ARTIST))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("exceeds maximum limit of 500 characters");
    }

    @Test
    @DisplayName("Throws NullPointerException when strategy is null")
    void throwsOnNullStrategy() {
        assertThatThrownBy(() -> new SearchQuery("Valid Query", null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("SearchQueryStrategy must not be null");
    }

    @Test
    @DisplayName("Throws IllegalArgumentException when priority is less than 1")
    void throwsOnInvalidPriority() {
        assertThatThrownBy(() -> new SearchQuery("Valid Query", SearchQueryStrategy.PRIMARY_TITLE_ARTIST, 0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Priority must be at least 1");
    }
}
