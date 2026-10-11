package com.spotifyyoutube.migrator.matching.normalization;

/**
 * Structured details preserving live performance venue, event, or location context.
 *
 * @param venueOrEvent specific venue or event text if identified (e.g. "at Wembley"), otherwise null
 * @param rawDescriptor raw live performance descriptor text extracted from source
 */
public record LivePerformanceDetails(
        String venueOrEvent,
        String rawDescriptor
) {
    public LivePerformanceDetails {
        rawDescriptor = rawDescriptor != null ? rawDescriptor : "";
    }
}
