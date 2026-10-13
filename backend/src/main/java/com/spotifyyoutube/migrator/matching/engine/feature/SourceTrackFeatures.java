package com.spotifyyoutube.migrator.matching.engine.feature;

import com.spotifyyoutube.migrator.matching.normalization.NormalizedTrackMetadata;

import java.time.Duration;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Immutable container for comparison features extracted from a normalized source Spotify track.
 * Directly reuses and exposes the contracts from Phase 5 {@link NormalizedTrackMetadata} without
 * modifying underlying models or fabricating missing metadata.
 *
 * @param sourceTrackId optional source track identifier (e.g. Spotify track ID), or null if not provided
 * @param normalizedTitle normalized title string from Phase 5
 * @param cleanTitle title stripped of promotional suffixes and variant markers
 * @param primaryArtist normalized primary artist name
 * @param normalizedArtists unmodifiable list of all discrete normalized artists
 * @param featuredArtists unmodifiable list of consolidated featured artists
 * @param normalizedAlbum normalized album title, or empty string if absent
 * @param duration canonical duration, or null if absent from source
 * @param durationMs duration in milliseconds, or null if duration is absent
 * @param hasAlbum whether album metadata is present
 * @param hasDuration whether duration is present
 * @param hasPrimaryArtist whether primary artist is non-empty
 * @param versionIndicators unmodifiable set of recognized version indicators on source track
 * @param isClean whether source title is free of variant and promotional markers
 * @param isLive whether source track represents a live recording
 * @param isRemaster whether source track represents a remaster
 * @param isAcoustic whether source track represents an acoustic version
 * @param sourceMetadata underlying original normalized metadata record
 */
public record SourceTrackFeatures(
        String sourceTrackId,
        String normalizedTitle,
        String cleanTitle,
        String primaryArtist,
        List<String> normalizedArtists,
        List<String> featuredArtists,
        String normalizedAlbum,
        Duration duration,
        Long durationMs,
        boolean hasAlbum,
        boolean hasDuration,
        boolean hasPrimaryArtist,
        Set<VersionIndicator> versionIndicators,
        boolean isClean,
        boolean isLive,
        boolean isRemaster,
        boolean isAcoustic,
        NormalizedTrackMetadata sourceMetadata
) {
    public SourceTrackFeatures {
        Objects.requireNonNull(sourceMetadata, "NormalizedTrackMetadata must not be null");
        sourceTrackId = sourceTrackId != null ? sourceTrackId.trim() : null;
        normalizedTitle = normalizedTitle != null ? normalizedTitle : "";
        cleanTitle = cleanTitle != null ? cleanTitle : "";
        primaryArtist = primaryArtist != null ? primaryArtist : "";
        normalizedArtists = normalizedArtists != null ? List.copyOf(normalizedArtists) : List.of();
        featuredArtists = featuredArtists != null ? List.copyOf(featuredArtists) : List.of();
        normalizedAlbum = normalizedAlbum != null ? normalizedAlbum : "";
        versionIndicators = versionIndicators != null
                ? Collections.unmodifiableSet(new LinkedHashSet<>(versionIndicators))
                : Collections.emptySet();
    }

    public Optional<String> getSourceTrackId() {
        return Optional.ofNullable(sourceTrackId);
    }

    public Optional<Duration> getDuration() {
        return Optional.ofNullable(duration);
    }

    public Optional<Long> getDurationMs() {
        return Optional.ofNullable(durationMs);
    }

    public boolean hasIndicator(VersionIndicator indicator) {
        return versionIndicators.contains(indicator);
    }
}
