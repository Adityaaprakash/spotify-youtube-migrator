package com.spotifyyoutube.migrator.matching.normalization;

import java.time.Duration;
import java.util.List;
import java.util.Set;

/**
 * Comprehensive, immutable structured result produced by {@link NormalizationOrchestrator}.
 * Aggregates the results of specialized normalizers into a single comparison-oriented model
 * for downstream candidate discovery and similarity matching.
 *
 * @param rawMetadata the original raw metadata inputs
 * @param normalizedTitle normalized title string from {@link TitleNormalizer}
 * @param normalizedArtistCredit structured artist credit from {@link ArtistNormalizer#parseArtistCredit}
 * @param normalizedArtists unmodifiable list of normalized artist names from {@link ArtistNormalizer#normalizeArtistList}
 * @param allFeaturedArtists consolidated, deduplicated list of all recognized featured artists
 * @param normalizedAlbum normalized album title from {@link AlbumNormalizer}
 * @param duration canonical duration from {@link DurationNormalizer}, or null if absent
 * @param tokenizedTitle structured title tokenization result from {@link MetadataTokenizer}
 */
public record NormalizedTrackMetadata(
        RawTrackMetadata rawMetadata,
        String normalizedTitle,
        NormalizedArtistCredit normalizedArtistCredit,
        List<String> normalizedArtists,
        List<String> allFeaturedArtists,
        String normalizedAlbum,
        Duration duration,
        TokenizedTitle tokenizedTitle
) {
    public NormalizedTrackMetadata {
        normalizedTitle = normalizedTitle != null ? normalizedTitle : "";
        normalizedArtistCredit = normalizedArtistCredit != null
                ? normalizedArtistCredit
                : new NormalizedArtistCredit("", List.of());
        normalizedArtists = normalizedArtists != null ? List.copyOf(normalizedArtists) : List.of();
        allFeaturedArtists = allFeaturedArtists != null ? List.copyOf(allFeaturedArtists) : List.of();
        normalizedAlbum = normalizedAlbum != null ? normalizedAlbum : "";
        tokenizedTitle = tokenizedTitle != null
                ? tokenizedTitle
                : new TokenizedTitle("", "", false, Set.of(), null, null, Set.of(), List.of(), List.of(), List.of());
    }

    /**
     * Title with promotional suffixes and recording variants stripped.
     */
    public String cleanTitle() {
        return tokenizedTitle.cleanTitle();
    }

    /**
     * Primary normalized artist name.
     */
    public String primaryArtist() {
        return normalizedArtistCredit.primaryArtist();
    }

    /**
     * Consolidated list of featured artists.
     */
    public List<String> featuredArtists() {
        return allFeaturedArtists;
    }

    /**
     * Unmodifiable set of recognized recording and release variants in encounter order.
     */
    public Set<VariantType> variants() {
        return tokenizedTitle.variants();
    }

    /**
     * Checks whether a specific variant type is present.
     */
    public boolean hasVariant(VariantType type) {
        return tokenizedTitle.hasVariant(type);
    }

    public boolean isLive() {
        return tokenizedTitle.isLive();
    }

    public boolean isRemaster() {
        return tokenizedTitle.isRemaster();
    }

    public boolean isAcoustic() {
        return tokenizedTitle.isAcoustic();
    }

    public boolean isOfficial() {
        return tokenizedTitle.isOfficial();
    }

    /**
     * Indicates whether the track is free of any recognized recording or release variants,
     * promotional suffixes, or media format markers.
     * <p>
     * <b>Note on semantics:</b> Refers strictly to syntactic title cleanliness (a standard
     * recording without variant modifiers such as live, acoustic, remix, or promotional video tags),
     * and does <i>not</i> represent parental advisory or censorship status (clean vs. explicit lyrics).
     *
     * @return true if the track has no detected variants, media formats, or promotional indicators
     */
    public boolean isClean() {
        return tokenizedTitle.isClean();
    }

    public Set<MediaFormat> mediaFormats() {
        return tokenizedTitle.mediaFormats();
    }

    public RemasterDetails remasterDetails() {
        return tokenizedTitle.remasterDetails();
    }

    public LivePerformanceDetails liveDetails() {
        return tokenizedTitle.liveDetails();
    }

    public List<String> unknownAnnotations() {
        return tokenizedTitle.unknownAnnotations();
    }
}
