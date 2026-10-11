package com.spotifyyoutube.migrator.matching.normalization;

import java.time.Duration;
import java.util.List;

/**
 * Immutable container for raw, unnormalized track metadata inputs.
 * Supports heterogeneous provider representations including discrete artist credits,
 * multi-artist lists, millisecond durations, and ISO-8601 duration strings.
 *
 * @param title raw track title
 * @param artistCredit raw primary artist credit string (e.g. "Dua Lipa feat. DaBaby")
 * @param artists raw discrete list of artist names (e.g. ["Dua Lipa", "DaBaby"])
 * @param album raw album name
 * @param duration explicit duration if already parsed
 * @param durationMs raw duration in milliseconds (Spotify-style)
 * @param durationIso8601 raw ISO-8601 duration string (YouTube-style)
 */
public record RawTrackMetadata(
        String title,
        String artistCredit,
        List<String> artists,
        String album,
        Duration duration,
        Long durationMs,
        String durationIso8601
) {
    public RawTrackMetadata {
        title = title != null ? title : "";
        artistCredit = artistCredit != null ? artistCredit : "";
        artists = artists != null ? List.copyOf(artists) : List.of();
        album = album != null ? album : "";
    }

    public static Builder builder() {
        return new Builder();
    }

    public static RawTrackMetadata of(String title, String artistCredit, String album, Long durationMs) {
        return new RawTrackMetadata(title, artistCredit, List.of(), album, null, durationMs, null);
    }

    public static RawTrackMetadata of(String title, String artistCredit, String album, Duration duration) {
        return new RawTrackMetadata(title, artistCredit, List.of(), album, duration, null, null);
    }

    public static RawTrackMetadata of(String title, String artistCredit, String album, String durationIso8601) {
        return new RawTrackMetadata(title, artistCredit, List.of(), album, null, null, durationIso8601);
    }

    public static RawTrackMetadata of(String title, List<String> artists, String album, Long durationMs) {
        return new RawTrackMetadata(title, null, artists, album, null, durationMs, null);
    }

    public static RawTrackMetadata of(String title, List<String> artists, String album, Duration duration) {
        return new RawTrackMetadata(title, null, artists, album, duration, null, null);
    }

    public static final class Builder {
        private String title;
        private String artistCredit;
        private List<String> artists;
        private String album;
        private Duration duration;
        private Long durationMs;
        private String durationIso8601;

        public Builder title(String title) {
            this.title = title;
            return this;
        }

        public Builder artistCredit(String artistCredit) {
            this.artistCredit = artistCredit;
            return this;
        }

        public Builder artists(List<String> artists) {
            this.artists = artists;
            return this;
        }

        public Builder album(String album) {
            this.album = album;
            return this;
        }

        public Builder duration(Duration duration) {
            this.duration = duration;
            return this;
        }

        public Builder durationMs(Long durationMs) {
            this.durationMs = durationMs;
            return this;
        }

        public Builder durationMs(Integer durationMs) {
            this.durationMs = durationMs != null ? durationMs.longValue() : null;
            return this;
        }

        public Builder durationIso8601(String durationIso8601) {
            this.durationIso8601 = durationIso8601;
            return this;
        }

        public RawTrackMetadata build() {
            return new RawTrackMetadata(title, artistCredit, artists, album, duration, durationMs, durationIso8601);
        }
    }
}
