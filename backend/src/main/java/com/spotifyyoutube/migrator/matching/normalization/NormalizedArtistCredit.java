package com.spotifyyoutube.migrator.matching.normalization;

import java.util.List;

/**
 * Structured representation of a normalized artist credit.
 * Separates the primary artist from any explicitly credited featured artists,
 * preserving original artist attribution and identity.
 *
 * @param primaryArtist normalized primary artist name, or empty string if input was blank
 * @param featuredArtists immutable list of normalized featured artist names
 */
public record NormalizedArtistCredit(
        String primaryArtist,
        List<String> featuredArtists
) {
    public NormalizedArtistCredit {
        primaryArtist = primaryArtist != null ? primaryArtist : "";
        featuredArtists = featuredArtists != null ? List.copyOf(featuredArtists) : List.of();
    }
}
