package com.spotifyyoutube.migrator.matching.discovery;

/**
 * Enumeration of deterministic search query generation strategies used to discover YouTube candidates.
 * Ordered conceptually from highest specificity/fidelity to broader fallback queries.
 */
public enum SearchQueryStrategy {
    /**
     * Primary artist and clean title (e.g., "Daft Punk - Get Lucky").
     * The primary, highest-fidelity query.
     */
    PRIMARY_TITLE_ARTIST,

    /**
     * Primary artist, featured artists, and clean title (e.g., "Daft Punk feat. Pharrell Williams - Get Lucky").
     * Used when the track has identified featured or guest artists.
     */
    TITLE_ARTIST_FEATURED,

    /**
     * Primary artist, clean title, and album title (e.g., "Daft Punk - Get Lucky Random Access Memories").
     * Used to disambiguate common titles or match album releases.
     */
    TITLE_ARTIST_ALBUM,

    /**
     * Primary artist and title preserving specific recording/version qualifiers (e.g., "Daft Punk - Get Lucky Live").
     * Used when the track has non-standard version variants such as Live, Acoustic, or Remix.
     */
    TITLE_ARTIST_VERSION,

    /**
     * Raw title and raw primary artist as provided by the source, without promotional suffix stripping.
     * Used as a high-fidelity fallback when clean title matching yields no results.
     */
    RAW_TITLE_AND_ARTIST,

    /**
     * Clean title only, without artist name.
     * Used as a broad fallback for tracks with obscure, non-standard, or instrumental/soundtrack credits.
     */
    CLEAN_TITLE_ONLY
}
