package com.spotifyyoutube.migrator.matching.discovery;

import com.spotifyyoutube.migrator.matching.normalization.NormalizedArtistCredit;
import com.spotifyyoutube.migrator.matching.normalization.NormalizedTrackMetadata;
import com.spotifyyoutube.migrator.matching.normalization.RawTrackMetadata;
import com.spotifyyoutube.migrator.matching.normalization.TokenizedTitle;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CandidateDiscoveryRequestTest {

    private NormalizedTrackMetadata sampleMetadata() {
        return new NormalizedTrackMetadata(
                RawTrackMetadata.of("Get Lucky", "Daft Punk", "Random Access Memories", 248000L),
                "Get Lucky",
                new NormalizedArtistCredit("Daft Punk", List.of("Pharrell Williams")),
                List.of("Daft Punk", "Pharrell Williams"),
                List.of("Pharrell Williams"),
                "Random Access Memories",
                Duration.ofMillis(248000),
                new TokenizedTitle("Get Lucky", "Get Lucky", false, Set.of(), null, null, Set.of(), List.of(), List.of(), List.of())
        );
    }

    @Test
    @DisplayName("Single-argument constructor uses default DiscoveryOptions")
    void singleArgumentConstructorDefaultsOptions() {
        NormalizedTrackMetadata metadata = sampleMetadata();
        CandidateDiscoveryRequest request = new CandidateDiscoveryRequest(metadata);

        assertThat(request.trackMetadata()).isSameAs(metadata);
        assertThat(request.options()).isEqualTo(DiscoveryOptions.defaults());
    }

    @Test
    @DisplayName("Two-argument constructor preserves custom options")
    void twoArgumentConstructorPreservesCustomOptions() {
        NormalizedTrackMetadata metadata = sampleMetadata();
        DiscoveryOptions customOptions = new DiscoveryOptions(10, 1, 5, Set.of(SearchQueryStrategy.PRIMARY_TITLE_ARTIST));
        CandidateDiscoveryRequest request = new CandidateDiscoveryRequest(metadata, customOptions);

        assertThat(request.trackMetadata()).isSameAs(metadata);
        assertThat(request.options()).isEqualTo(customOptions);
    }

    @Test
    @DisplayName("Null options argument falls back to default DiscoveryOptions")
    void nullOptionsFallsBackToDefaults() {
        NormalizedTrackMetadata metadata = sampleMetadata();
        CandidateDiscoveryRequest request = new CandidateDiscoveryRequest(metadata, null);

        assertThat(request.options()).isEqualTo(DiscoveryOptions.defaults());
    }

    @Test
    @DisplayName("Throws NullPointerException when trackMetadata is null")
    void throwsOnNullTrackMetadata() {
        assertThatThrownBy(() -> new CandidateDiscoveryRequest(null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("NormalizedTrackMetadata must not be null");
    }
}
