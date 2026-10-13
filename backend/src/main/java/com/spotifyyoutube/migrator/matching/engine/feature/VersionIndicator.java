package com.spotifyyoutube.migrator.matching.engine.feature;

/**
 * Standardized categorization of audio recording and video content indicators.
 * Used during candidate feature extraction to capture comparison evidence without
 * prematurely assigning numerical penalties.
 */
public enum VersionIndicator {

    /**
     * Cover recording performed by an alternate artist, band, or amateur creator.
     */
    COVER,

    /**
     * Live concert, festival, broadcast, or in-studio performance recording.
     */
    LIVE,

    /**
     * Remix, club mix, VIP edit, or alternate dance production.
     */
    REMIX,

    /**
     * Karaoke, backing track, sing-along, or instrumental accompaniment version.
     */
    KARAOKE,

    /**
     * Reaction video, commentary, or review by a content creator.
     */
    REACTION,

    /**
     * Nightcore version (accelerated tempo with pitched-up vocals).
     */
    NIGHTCORE,

    /**
     * Sped-up, fast tempo, or accelerated track version.
     */
    SPED_UP,

    /**
     * Slowed down, chopped and screwed, or slowed + reverb version.
     */
    SLOWED,

    /**
     * Acoustic or unplugged arrangement.
     */
    ACOUSTIC,

    /**
     * Instrumental or vocals-removed version.
     */
    INSTRUMENTAL,

    /**
     * Remastered audio release or anniversary edition.
     */
    REMASTER
}
