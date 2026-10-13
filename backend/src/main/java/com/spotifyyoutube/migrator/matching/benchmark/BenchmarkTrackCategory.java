package com.spotifyyoutube.migrator.matching.benchmark;

/**
 * Typology of benchmark tracks enabling stratified evaluation across diverse matching challenges.
 */
public enum BenchmarkTrackCategory {

    /**
     * Standard popular track with exact title and primary artist credit.
     */
    STANDARD_POPULAR,

    /**
     * Track whose verified destination is an official music video.
     */
    OFFICIAL_MUSIC_VIDEO,

    /**
     * Track whose verified destination is an official audio upload.
     */
    OFFICIAL_AUDIO,

    /**
     * Track whose verified destination is an auto-generated YouTube Topic channel release.
     */
    TOPIC_CHANNEL,

    /**
     * Track featuring guest or featured artists with complex credit formatting.
     */
    FEATURED_ARTIST,

    /**
     * Multi-member band or collaboration with ampersands, commas, or plus signs.
     */
    BAND_COLLABORATION,

    /**
     * Official remix, club mix, or alternate producer version.
     */
    REMIX,

    /**
     * Live concert or festival performance recording.
     */
    LIVE,

    /**
     * Acoustic or unplugged arrangement.
     */
    ACOUSTIC,

    /**
     * Cover recording or tribute performance.
     */
    COVER,

    /**
     * Remastered release or anniversary album edition.
     */
    REMASTER,

    /**
     * Explicit vs. clean radio edit variant.
     */
    EXPLICIT_CLEAN,

    /**
     * Track with diacritics, non-Latin script, curly quotes, dashes, or minor typographical variations.
     */
    TYPO_PUNCTUATION,

    /**
     * Very short track title (&lt;= 3 characters) presenting high substring collision risk.
     */
    SHORT_TITLE,

    /**
     * Distinct song sharing the exact same title with a completely different artist.
     */
    SAME_TITLE_DIFFERENT_ARTIST,

    /**
     * Track where duration alignment is the primary discriminator between versions.
     */
    DURATION_DISCREPANCY,

    /**
     * Legitimate non-match: unreleased track, obscure local file, or catalog absence on YouTube.
     */
    LEGITIMATE_NO_MATCH
}
