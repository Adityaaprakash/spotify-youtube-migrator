package com.spotifyyoutube.migrator.matching.discovery;

import com.spotifyyoutube.migrator.migration.application.destination.model.DestinationTrackCandidate;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DiscoveredCandidateTest {

    private DestinationTrackCandidate sampleCandidate() {
        return new DestinationTrackCandidate(
                "dQw4w9WgXcQ",
                "Rick Astley - Never Gonna Give You Up (Official Music Video)",
                "Rick Astley",
                "Official video description",
                "https://i.ytimg.com/vi/dQw4w9WgXcQ/default.jpg",
                "2009-10-25T06:57:33Z"
        );
    }

    private SearchQuery sampleQuery() {
        return new SearchQuery("Rick Astley - Never Gonna Give You Up", SearchQueryStrategy.PRIMARY_TITLE_ARTIST);
    }

    @Test
    @DisplayName("Creates DiscoveredCandidate and delegates accessors to candidate properties")
    void createsCandidateAndDelegatesAccessors() {
        DestinationTrackCandidate candidate = sampleCandidate();
        SearchQuery query = sampleQuery();
        DiscoveredCandidate discovered = new DiscoveredCandidate(candidate, query);

        assertThat(discovered.candidate()).isSameAs(candidate);
        assertThat(discovered.discoveredBy()).isSameAs(query);
        assertThat(discovered.id()).isEqualTo("dQw4w9WgXcQ");
        assertThat(discovered.title()).isEqualTo("Rick Astley - Never Gonna Give You Up (Official Music Video)");
        assertThat(discovered.channelOrArtist()).isEqualTo("Rick Astley");
        assertThat(discovered.description()).isEqualTo("Official video description");
        assertThat(discovered.thumbnailUrl()).isEqualTo("https://i.ytimg.com/vi/dQw4w9WgXcQ/default.jpg");
        assertThat(discovered.publishedAt()).isEqualTo("2009-10-25T06:57:33Z");
        assertThat(discovered.duration()).isNull();
    }

    @Test
    @DisplayName("Delegates duration accessor when candidate has non-null duration")
    void delegatesDurationAccessorWhenPresent() {
        DestinationTrackCandidate candidateWithDuration = new DestinationTrackCandidate(
                "dQw4w9WgXcQ",
                "Rick Astley - Never Gonna Give You Up",
                "Rick Astley",
                "Description",
                "thumb",
                "2009",
                java.time.Duration.ofSeconds(213)
        );
        SearchQuery query = sampleQuery();
        DiscoveredCandidate discovered = new DiscoveredCandidate(candidateWithDuration, query);

        assertThat(discovered.duration()).isEqualTo(java.time.Duration.ofSeconds(213));
    }

    @Test
    @DisplayName("Throws NullPointerException when candidate is null")
    void throwsOnNullCandidate() {
        SearchQuery query = sampleQuery();

        assertThatThrownBy(() -> new DiscoveredCandidate(null, query))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("DestinationTrackCandidate must not be null");
    }

    @Test
    @DisplayName("Throws NullPointerException when query is null")
    void throwsOnNullQuery() {
        DestinationTrackCandidate candidate = sampleCandidate();

        assertThatThrownBy(() -> new DiscoveredCandidate(candidate, null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("SearchQuery must not be null");
    }
}
