package com.spotifyyoutube.migrator.matching.benchmark;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Immutable representation of a source Spotify track in a benchmark example.
 *
 * @param id optional Spotify track identifier (e.g., Spotify URI or 22-character ID), or null
 * @param title non-null, non-blank original track title
 * @param primaryArtist non-null, non-blank primary artist name
 * @param artists unmodifiable list of all credited discrete artist names
 * @param album optional album title, or null if uncredited
 * @param durationMs optional playback duration in milliseconds, or null if unmeasured
 * @param isExplicit optional flag indicating explicit content advisory, or null if unknown
 * @param isrc optional International Standard Recording Code, or null if unrecorded
 */
public record BenchmarkSourceTrack(
        String id,
        String title,
        String primaryArtist,
        List<String> artists,
        String album,
        Long durationMs,
        Boolean isExplicit,
        String isrc
) {

    public BenchmarkSourceTrack {
        Objects.requireNonNull(title, "title must not be null");
        if (title.isBlank()) {
            throw new IllegalArgumentException("title must not be blank");
        }
        Objects.requireNonNull(primaryArtist, "primaryArtist must not be null");
        if (primaryArtist.isBlank()) {
            throw new IllegalArgumentException("primaryArtist must not be blank");
        }
        id = id != null ? id.trim() : null;
        album = album != null ? album.trim() : null;
        isrc = isrc != null ? isrc.trim() : null;
        if (durationMs != null && durationMs <= 0) {
            throw new IllegalArgumentException("durationMs must be positive: " + durationMs);
        }
        artists = artists != null ? List.copyOf(artists) : List.of(primaryArtist);
    }

    /**
     * Convenience factory for standard source track creation.
     *
     * @param id Spotify ID or URI
     * @param title track title
     * @param primaryArtist primary artist
     * @param album album title
     * @param durationMs duration in milliseconds
     * @return constructed BenchmarkSourceTrack
     */
    public static BenchmarkSourceTrack of(
            String id,
            String title,
            String primaryArtist,
            String album,
            Long durationMs
    ) {
        return new BenchmarkSourceTrack(
                id,
                title,
                primaryArtist,
                List.of(primaryArtist),
                album,
                durationMs,
                null,
                null
        );
    }

    /**
     * Convenience factory with discrete artist credits.
     *
     * @param id Spotify ID or URI
     * @param title track title
     * @param primaryArtist primary artist
     * @param artists all credited artists
     * @param album album title
     * @param durationMs duration in milliseconds
     * @return constructed BenchmarkSourceTrack
     */
    public static BenchmarkSourceTrack of(
            String id,
            String title,
            String primaryArtist,
            List<String> artists,
            String album,
            Long durationMs
    ) {
        return new BenchmarkSourceTrack(
                id,
                title,
                primaryArtist,
                artists,
                album,
                durationMs,
                null,
                null
        );
    }

    public Optional<String> getId() {
        return Optional.ofNullable(id);
    }

    public Optional<String> getAlbum() {
        return Optional.ofNullable(album);
    }

    public Optional<Long> getDurationMs() {
        return Optional.ofNullable(durationMs);
    }

    public Optional<Boolean> getIsExplicit() {
        return Optional.ofNullable(isExplicit);
    }

    public Optional<String> getIsrc() {
        return Optional.ofNullable(isrc);
    }
}
