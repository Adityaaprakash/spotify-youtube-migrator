package com.spotifyyoutube.migrator.matching.normalization;

import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Orchestrates the track metadata normalization pipeline for deterministic track matching.
 * Coordinates {@link TitleNormalizer}, {@link ArtistNormalizer}, {@link AlbumNormalizer},
 * {@link DurationNormalizer}, and {@link MetadataTokenizer} into a single, cohesive,
 * provider-neutral entry point.
 */
@Component
public class NormalizationOrchestrator {

    private final TitleNormalizer titleNormalizer;
    private final ArtistNormalizer artistNormalizer;
    private final AlbumNormalizer albumNormalizer;
    private final DurationNormalizer durationNormalizer;
    private final MetadataTokenizer metadataTokenizer;

    public NormalizationOrchestrator(
            TitleNormalizer titleNormalizer,
            ArtistNormalizer artistNormalizer,
            AlbumNormalizer albumNormalizer,
            DurationNormalizer durationNormalizer,
            MetadataTokenizer metadataTokenizer
    ) {
        this.titleNormalizer = Objects.requireNonNull(titleNormalizer, "titleNormalizer cannot be null");
        this.artistNormalizer = Objects.requireNonNull(artistNormalizer, "artistNormalizer cannot be null");
        this.albumNormalizer = Objects.requireNonNull(albumNormalizer, "albumNormalizer cannot be null");
        this.durationNormalizer = Objects.requireNonNull(durationNormalizer, "durationNormalizer cannot be null");
        this.metadataTokenizer = Objects.requireNonNull(metadataTokenizer, "metadataTokenizer cannot be null");
    }

    /**
     * Normalizes a raw track metadata container into structured {@link NormalizedTrackMetadata}.
     *
     * @param raw the raw track metadata container, must not be null
     * @return the normalized track metadata
     * @throws IllegalArgumentException if raw is null, or if duration values are invalid/negative
     */
    public NormalizedTrackMetadata normalize(RawTrackMetadata raw) {
        if (raw == null) {
            throw new IllegalArgumentException("RawTrackMetadata cannot be null");
        }

        // 1. Title normalization & metadata tokenization
        String rawTitle = raw.title();
        String normalizedTitle = titleNormalizer.normalize(rawTitle);
        TokenizedTitle tokenizedTitle = metadataTokenizer.tokenize(rawTitle);

        // 2. Artist credit and artist list normalization
        NormalizedArtistCredit normalizedCredit;
        List<String> normalizedArtists;

        if (raw.artistCredit() != null && !raw.artistCredit().isBlank()) {
            normalizedCredit = artistNormalizer.parseArtistCredit(raw.artistCredit());
            if (raw.artists() != null && !raw.artists().isEmpty()) {
                normalizedArtists = artistNormalizer.normalizeArtistList(raw.artists());
            } else if (!normalizedCredit.primaryArtist().isEmpty()) {
                normalizedArtists = List.of(normalizedCredit.primaryArtist());
            } else {
                normalizedArtists = List.of();
            }
        } else if (raw.artists() != null && !raw.artists().isEmpty()) {
            normalizedArtists = artistNormalizer.normalizeArtistList(raw.artists());
            String primary = !normalizedArtists.isEmpty() ? normalizedArtists.get(0) : "";
            List<String> featured = normalizedArtists.size() > 1
                    ? normalizedArtists.subList(1, normalizedArtists.size())
                    : List.of();
            normalizedCredit = new NormalizedArtistCredit(primary, featured);
        } else {
            normalizedCredit = new NormalizedArtistCredit("", List.of());
            normalizedArtists = List.of();
        }

        // 3. Consolidate featured artists from artist credit, discrete artist list, and title tokenization
        Set<String> featuredSet = new LinkedHashSet<>(normalizedCredit.featuredArtists());
        String primaryArtist = normalizedCredit.primaryArtist();

        if (normalizedArtists.size() > 1) {
            for (String artist : normalizedArtists.subList(1, normalizedArtists.size())) {
                if (!artist.isEmpty() && !artist.equalsIgnoreCase(primaryArtist)) {
                    featuredSet.add(artist);
                }
            }
        }

        for (String titleFeatured : tokenizedTitle.featuredArtists()) {
            String norm = artistNormalizer.normalize(titleFeatured);
            if (!norm.isEmpty() && !norm.equalsIgnoreCase(primaryArtist)) {
                featuredSet.add(norm);
            }
        }
        List<String> allFeaturedArtists = List.copyOf(featuredSet);

        // 4. Album normalization
        String normalizedAlbum = albumNormalizer.normalize(raw.album());

        // 5. Duration normalization (canonical Duration)
        Duration canonicalDuration = resolveDuration(raw);

        return new NormalizedTrackMetadata(
                raw,
                normalizedTitle,
                normalizedCredit,
                normalizedArtists,
                allFeaturedArtists,
                normalizedAlbum,
                canonicalDuration,
                tokenizedTitle
        );
    }

    private Duration resolveDuration(RawTrackMetadata raw) {
        Duration direct = raw.duration();
        if (direct != null && direct.isNegative()) {
            throw new IllegalArgumentException("Direct duration cannot be negative: " + direct);
        }

        Duration fromMs = raw.durationMs() != null
                ? durationNormalizer.fromMilliseconds(raw.durationMs())
                : null;

        Duration fromIso = (raw.durationIso8601() != null && !raw.durationIso8601().isBlank())
                ? durationNormalizer.fromIso8601(raw.durationIso8601())
                : null;

        Duration resolved = null;

        if (direct != null) {
            resolved = direct;
        }

        if (fromMs != null) {
            if (resolved != null && !resolved.equals(fromMs)) {
                throw new IllegalArgumentException(
                        "Conflicting durations provided in RawTrackMetadata: " + resolved + " vs " + fromMs);
            }
            resolved = fromMs;
        }

        if (fromIso != null) {
            if (resolved != null && !resolved.equals(fromIso)) {
                throw new IllegalArgumentException(
                        "Conflicting durations provided in RawTrackMetadata: " + resolved + " vs " + fromIso);
            }
            resolved = fromIso;
        }

        return resolved;
    }

    // Overloaded convenience methods for direct usage
    public NormalizedTrackMetadata normalize(String title, String artistCredit, String album, Long durationMs) {
        return normalize(RawTrackMetadata.of(title, artistCredit, album, durationMs));
    }

    public NormalizedTrackMetadata normalize(String title, String artistCredit, String album, Integer durationMs) {
        Long ms = durationMs != null ? durationMs.longValue() : null;
        return normalize(RawTrackMetadata.of(title, artistCredit, album, ms));
    }

    public NormalizedTrackMetadata normalize(String title, String artistCredit, String album, String durationIso8601) {
        return normalize(RawTrackMetadata.of(title, artistCredit, album, durationIso8601));
    }

    public NormalizedTrackMetadata normalize(String title, String artistCredit, String album, Duration duration) {
        return normalize(RawTrackMetadata.of(title, artistCredit, album, duration));
    }

    public NormalizedTrackMetadata normalize(String title, List<String> artists, String album, Long durationMs) {
        return normalize(RawTrackMetadata.of(title, artists, album, durationMs));
    }

    public NormalizedTrackMetadata normalize(String title, List<String> artists, String album, Integer durationMs) {
        Long ms = durationMs != null ? durationMs.longValue() : null;
        return normalize(RawTrackMetadata.of(title, artists, album, ms));
    }

    public NormalizedTrackMetadata normalize(String title, List<String> artists, String album, Duration duration) {
        return normalize(RawTrackMetadata.of(title, artists, album, duration));
    }
}
