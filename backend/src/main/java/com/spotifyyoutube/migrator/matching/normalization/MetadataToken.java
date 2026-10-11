package com.spotifyyoutube.migrator.matching.normalization;

/**
 * Immutable token representing a discrete metadata annotation extracted from track metadata.
 *
 * @param category high-level category of the token
 * @param value normalized value or semantic identifier
 * @param rawText original raw text substring that produced this token
 */
public record MetadataToken(
        TokenCategory category,
        String value,
        String rawText
) {
    public MetadataToken {
        if (category == null) {
            throw new IllegalArgumentException("Token category cannot be null");
        }
        value = value != null ? value : "";
        rawText = rawText != null ? rawText : "";
    }
}
