package com.spotifyyoutube.migrator.matching.normalization;

/**
 * Structured details preserving remaster information and release year.
 *
 * @param year four-digit release or remaster year if present, otherwise null
 * @param rawDescriptor raw remaster descriptor text extracted from source
 */
public record RemasterDetails(
        Integer year,
        String rawDescriptor
) {
    public RemasterDetails {
        rawDescriptor = rawDescriptor != null ? rawDescriptor : "";
    }
}
