package com.spotifyyoutube.migrator.migration.application.destination.model;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DestinationTrackCandidateTest {

    @Test
    @DisplayName("6-argument constructor creates candidate with null duration")
    void sixArgConstructor_createsCandidateWithNullDuration() {
        DestinationTrackCandidate candidate = new DestinationTrackCandidate(
                "vid123",
                "Get Lucky",
                "Daft Punk",
                "Official music video",
                "https://img.youtube.com/thumb.jpg",
                "2013-04-19T00:00:00Z"
        );

        assertThat(candidate.id()).isEqualTo("vid123");
        assertThat(candidate.title()).isEqualTo("Get Lucky");
        assertThat(candidate.channelOrArtist()).isEqualTo("Daft Punk");
        assertThat(candidate.description()).isEqualTo("Official music video");
        assertThat(candidate.thumbnailUrl()).isEqualTo("https://img.youtube.com/thumb.jpg");
        assertThat(candidate.publishedAt()).isEqualTo("2013-04-19T00:00:00Z");
        assertThat(candidate.duration()).isNull();
    }

    @Test
    @DisplayName("7-argument constructor with Duration accurately preserves duration")
    void sevenArgConstructor_withDuration_preservesDuration() {
        Duration duration = Duration.ofMinutes(4).plusSeconds(8);
        DestinationTrackCandidate candidate = new DestinationTrackCandidate(
                "vid123",
                "Get Lucky",
                "Daft Punk",
                "Official video",
                "https://img.youtube.com/thumb.jpg",
                "2013-04-19T00:00:00Z",
                duration
        );

        assertThat(candidate.duration()).isEqualTo(Duration.ofSeconds(248));
    }

    @Test
    @DisplayName("7-argument constructor with null Duration keeps duration as null (not zero)")
    void sevenArgConstructor_withNullDuration_keepsDurationNull() {
        DestinationTrackCandidate candidate = new DestinationTrackCandidate(
                "vid123", "Title", "Artist", "Desc", "url", "2023", (Duration) null
        );

        assertThat(candidate.duration()).isNull();
    }

    @Test
    @DisplayName("7-argument constructor with Duration.ZERO preserves zero duration")
    void sevenArgConstructor_withZeroDuration_preservesZero() {
        DestinationTrackCandidate candidate = new DestinationTrackCandidate(
                "vid123", "Title", "Artist", "Desc", "url", "2023", Duration.ZERO
        );

        assertThat(candidate.duration()).isEqualTo(Duration.ZERO);
    }

    @Test
    @DisplayName("Constructor throws IllegalArgumentException when Duration is negative")
    void constructor_withNegativeDuration_throwsIllegalArgumentException() {
        assertThatThrownBy(() -> new DestinationTrackCandidate(
                "vid123", "Title", "Artist", "Desc", "url", "2023", Duration.ofSeconds(-10)
        ))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Duration cannot be negative");
    }

    @Test
    @DisplayName("ISO-8601 string constructor correctly parses valid duration formats")
    void stringConstructor_parsesValidIso8601() {
        DestinationTrackCandidate c1 = new DestinationTrackCandidate(
                "v1", "T1", "A1", "D1", "u1", "2023", "PT3M45S"
        );
        assertThat(c1.duration()).isEqualTo(Duration.ofSeconds(225));

        DestinationTrackCandidate c2 = new DestinationTrackCandidate(
                "v2", "T2", "A2", "D2", "u2", "2023", "PT1H2M3S"
        );
        assertThat(c2.duration()).isEqualTo(Duration.ofSeconds(3723));

        DestinationTrackCandidate c3 = new DestinationTrackCandidate(
                "v3", "T3", "A3", "D3", "u3", "2023", "PT45S"
        );
        assertThat(c3.duration()).isEqualTo(Duration.ofSeconds(45));

        DestinationTrackCandidate c4 = new DestinationTrackCandidate(
                "v4", "T4", "A4", "D4", "u4", "2023", "  PT1M30S  "
        );
        assertThat(c4.duration()).isEqualTo(Duration.ofSeconds(90));
    }

    @Test
    @DisplayName("ISO-8601 string constructor preserves zero duration")
    void stringConstructor_parsesZeroDuration() {
        DestinationTrackCandidate c = new DestinationTrackCandidate(
                "v1", "T1", "A1", "D1", "u1", "2023", "PT0S"
        );
        assertThat(c.duration()).isEqualTo(Duration.ZERO);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "   ", "\t\n"})
    @DisplayName("ISO-8601 string constructor treats blank duration as null rather than zero")
    void stringConstructor_blankDuration_returnsNull(String blank) {
        DestinationTrackCandidate candidate = new DestinationTrackCandidate(
                "v1", "T1", "A1", "D1", "u1", "2023", blank
        );
        assertThat(candidate.duration()).isNull();
    }

    @Test
    @DisplayName("ISO-8601 string constructor treats null string as null rather than zero")
    void stringConstructor_nullString_returnsNull() {
        DestinationTrackCandidate candidate = new DestinationTrackCandidate(
                "v1", "T1", "A1", "D1", "u1", "2023", (String) null
        );
        assertThat(candidate.duration()).isNull();
    }

    @ParameterizedTest
    @ValueSource(strings = {"invalid", "PT", "abc", "123", "-PT5M", "PT-5M"})
    @DisplayName("ISO-8601 string constructor gracefully handles malformed duration strings without throwing")
    void stringConstructor_malformedDuration_returnsNull(String malformed) {
        DestinationTrackCandidate candidate = new DestinationTrackCandidate(
                "v1", "T1", "A1", "D1", "u1", "2023", malformed
        );
        assertThat(candidate.duration()).isNull();
    }

    @Test
    @DisplayName("fromDetails converts DestinationTrackDetails into DestinationTrackCandidate with duration")
    void fromDetails_convertsDetailsWithValidDuration() {
        DestinationTrackDetails details = new DestinationTrackDetails(
                "v99",
                "One More Time",
                "Daft Punk",
                "Electronic masterpiece",
                "https://img.youtube.com/thumb99.jpg",
                "2000-11-13T00:00:00Z",
                "PT5M20S"
        );

        DestinationTrackCandidate candidate = DestinationTrackCandidate.fromDetails(details);

        assertThat(candidate).isNotNull();
        assertThat(candidate.id()).isEqualTo("v99");
        assertThat(candidate.title()).isEqualTo("One More Time");
        assertThat(candidate.channelOrArtist()).isEqualTo("Daft Punk");
        assertThat(candidate.description()).isEqualTo("Electronic masterpiece");
        assertThat(candidate.thumbnailUrl()).isEqualTo("https://img.youtube.com/thumb99.jpg");
        assertThat(candidate.publishedAt()).isEqualTo("2000-11-13T00:00:00Z");
        assertThat(candidate.duration()).isEqualTo(Duration.ofSeconds(320));
    }

    @Test
    @DisplayName("fromDetails handles null or malformed duration in details gracefully")
    void fromDetails_handlesNullAndMalformedDuration() {
        DestinationTrackDetails nullDurationDetails = new DestinationTrackDetails(
                "v1", "T1", "A1", "D1", "u1", "2023", null
        );
        DestinationTrackCandidate c1 = DestinationTrackCandidate.fromDetails(nullDurationDetails);
        assertThat(c1.duration()).isNull();

        DestinationTrackDetails malformedDurationDetails = new DestinationTrackDetails(
                "v2", "T2", "A2", "D2", "u2", "2023", "not-a-duration"
        );
        DestinationTrackCandidate c2 = DestinationTrackCandidate.fromDetails(malformedDurationDetails);
        assertThat(c2.duration()).isNull();
    }

    @Test
    @DisplayName("fromDetails returns null when details input is null")
    void fromDetails_returnsNullWhenDetailsNull() {
        assertThat(DestinationTrackCandidate.fromDetails(null)).isNull();
    }

    @Test
    @DisplayName("Accepts null optional metadata without failure")
    void acceptsNullOptionalMetadata() {
        DestinationTrackCandidate candidate = new DestinationTrackCandidate(
                "vid1", null, null, null, null, null
        );

        assertThat(candidate.id()).isEqualTo("vid1");
        assertThat(candidate.title()).isNull();
        assertThat(candidate.channelOrArtist()).isNull();
        assertThat(candidate.description()).isNull();
        assertThat(candidate.thumbnailUrl()).isNull();
        assertThat(candidate.publishedAt()).isNull();
        assertThat(candidate.duration()).isNull();
    }

    @Test
    @DisplayName("Equality and hashCode consistency")
    void equalityAndHashCode() {
        DestinationTrackCandidate c1 = new DestinationTrackCandidate(
                "vid1", "Title", "Artist", "Desc", "url", "2023", Duration.ofMinutes(3)
        );
        DestinationTrackCandidate c2 = new DestinationTrackCandidate(
                "vid1", "Title", "Artist", "Desc", "url", "2023", Duration.ofMinutes(3)
        );
        DestinationTrackCandidate c3 = new DestinationTrackCandidate(
                "vid2", "Title", "Artist", "Desc", "url", "2023", Duration.ofMinutes(3)
        );

        assertThat(c1).isEqualTo(c2);
        assertThat(c1.hashCode()).isEqualTo(c2.hashCode());
        assertThat(c1).isNotEqualTo(c3);
    }

    @Test
    @DisplayName("Whitespace-padded video IDs are automatically trimmed in compact constructor")
    void whitespacePaddedVideoId_isTrimmed() {
        DestinationTrackCandidate candidate = new DestinationTrackCandidate(
                "   vid123   ", "Title", "Artist", "Desc", "url", "2023", Duration.ofMinutes(3)
        );

        assertThat(candidate.id()).isEqualTo("vid123");
    }
}
