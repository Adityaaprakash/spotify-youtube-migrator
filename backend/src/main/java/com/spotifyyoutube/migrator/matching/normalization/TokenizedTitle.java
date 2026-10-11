package com.spotifyyoutube.migrator.matching.normalization;

import java.util.List;
import java.util.Set;

/**
 * Immutable structured result produced by {@link MetadataTokenizer}.
 * Captures clean title text, identified official content indicators, media formats,
 * variant classifications, remaster details, performance context, and unclassified annotations.
 *
 * @param rawTitle original raw title passed as input
 * @param cleanTitle title text after stripping recognized promotional and variant markers
 * @param isOfficial true if an official content indicator was recognized
 * @param mediaFormats immutable set of recognized media format indicators
 * @param remasterDetails structured remaster details if present, otherwise null
 * @param liveDetails structured live performance details if present, otherwise null
 * @param variants immutable set of recognized variant classifications in deterministic order
 * @param tokens immutable list of all recognized metadata tokens in encounter order
 * @param unknownAnnotations immutable list of bracketed/parenthetical text not recognized as known variants
 * @param featuredArtists immutable list of featured artist names extracted from title parentheticals
 */
public record TokenizedTitle(
        String rawTitle,
        String cleanTitle,
        boolean isOfficial,
        Set<MediaFormat> mediaFormats,
        RemasterDetails remasterDetails,
        LivePerformanceDetails liveDetails,
        Set<VariantType> variants,
        List<MetadataToken> tokens,
        List<String> unknownAnnotations,
        List<String> featuredArtists
) {
    public TokenizedTitle {
        rawTitle = rawTitle != null ? rawTitle : "";
        cleanTitle = cleanTitle != null ? cleanTitle : "";
        mediaFormats = mediaFormats != null
                ? java.util.Collections.unmodifiableSet(new java.util.LinkedHashSet<>(mediaFormats))
                : Set.of();
        variants = variants != null
                ? java.util.Collections.unmodifiableSet(new java.util.LinkedHashSet<>(variants))
                : Set.of();
        tokens = tokens != null ? List.copyOf(tokens) : List.of();
        unknownAnnotations = unknownAnnotations != null ? List.copyOf(unknownAnnotations) : List.of();
        featuredArtists = featuredArtists != null ? List.copyOf(featuredArtists) : List.of();
    }

    public boolean hasVariant(VariantType type) {
        return variants.contains(type);
    }

    public boolean isLive() {
        return variants.contains(VariantType.LIVE);
    }

    public boolean isRemaster() {
        return variants.contains(VariantType.REMASTER);
    }

    public boolean isAcoustic() {
        return variants.contains(VariantType.ACOUSTIC);
    }

    public boolean isRemix() {
        return variants.contains(VariantType.REMIX);
    }

    public boolean isCover() {
        return variants.contains(VariantType.COVER);
    }

    public boolean isKaraoke() {
        return variants.contains(VariantType.KARAOKE);
    }

    public boolean isInstrumental() {
        return variants.contains(VariantType.INSTRUMENTAL);
    }

    /**
     * Indicates whether the title is clean of any recognized recording or release variants,
     * promotional annotations, or media format indicators.
     * <p>
     * <b>Note on semantics:</b> Refers strictly to syntactic title cleanliness (absence of
     * variant modifiers, promotional suffixes, or media format indicators in the title), and
     * does <i>not</i> represent parental advisory or explicit-content status (clean vs. explicit lyrics).
     *
     * @return true if no variants, media formats, or promotional tokens were detected in the title
     */
    public boolean isClean() {
        return variants.isEmpty() && mediaFormats.isEmpty() && !isOfficial;
    }
}
