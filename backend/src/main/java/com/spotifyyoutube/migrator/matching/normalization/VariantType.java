package com.spotifyyoutube.migrator.matching.normalization;

/**
 * Known categories of audio recording, release, or playback variants.
 * Used for deterministic matching and variant penalty calculation.
 */
public enum VariantType {

    // Remasters & Re-releases
    REMASTER,

    // Edits & Mixes
    RADIO_EDIT,
    EXTENDED_MIX,
    ORIGINAL_MIX,
    CLUB_MIX,
    INSTRUMENTAL,
    DEMO,

    // Performance & Recording Context
    LIVE,
    ACOUSTIC,
    UNPLUGGED,
    STUDIO,

    // Alternate & Derivative Versions
    REMIX,
    COVER,
    KARAOKE,
    NIGHTCORE,
    SPED_UP,
    SLOWED,
    SLOWED_REVERB,
    REVERB
}
