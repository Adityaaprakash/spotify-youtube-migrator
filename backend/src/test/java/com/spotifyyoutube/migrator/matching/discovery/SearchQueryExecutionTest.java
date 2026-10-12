package com.spotifyyoutube.migrator.matching.discovery;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SearchQueryExecutionTest {

    private SearchQuery sampleQuery() {
        return new SearchQuery("Daft Punk - Get Lucky", SearchQueryStrategy.PRIMARY_TITLE_ARTIST);
    }

    @Test
    @DisplayName("Success factory creates successful record with correct result count and null error")
    void successFactoryCreatesSuccessfulRecord() {
        SearchQuery query = sampleQuery();
        SearchQueryExecution execution = SearchQueryExecution.success(query, 5);

        assertThat(execution.query()).isSameAs(query);
        assertThat(execution.resultCount()).isEqualTo(5);
        assertThat(execution.successful()).isTrue();
        assertThat(execution.errorMessage()).isNull();
    }

    @Test
    @DisplayName("Failure factory creates failed record with 0 result count and provided error message")
    void failureFactoryCreatesFailedRecordWithErrorMessage() {
        SearchQuery query = sampleQuery();
        SearchQueryExecution execution = SearchQueryExecution.failure(query, "HTTP 403 Quota Exceeded");

        assertThat(execution.query()).isSameAs(query);
        assertThat(execution.resultCount()).isZero();
        assertThat(execution.successful()).isFalse();
        assertThat(execution.errorMessage()).isEqualTo("HTTP 403 Quota Exceeded");
    }

    @Test
    @DisplayName("Failure factory with null message provides default error message")
    void failureFactoryWithNullMessageProvidesDefault() {
        SearchQuery query = sampleQuery();
        SearchQueryExecution execution = SearchQueryExecution.failure(query, null);

        assertThat(execution.errorMessage()).isEqualTo("Unknown search error");
    }

    @Test
    @DisplayName("Throws NullPointerException when query is null")
    void throwsOnNullQuery() {
        assertThatThrownBy(() -> new SearchQueryExecution(null, 5, true, null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("SearchQuery must not be null");
    }

    @Test
    @DisplayName("Throws IllegalArgumentException when resultCount is negative")
    void throwsOnNegativeResultCount() {
        SearchQuery query = sampleQuery();

        assertThatThrownBy(() -> new SearchQueryExecution(query, -1, true, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Result count must not be negative");
    }
}
