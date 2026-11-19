package com.spotifyyoutube.migrator.common.metrics;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Centralized constant definitions and specifications for application metric names.
 *
 * <p>Follows standard Micrometer dot-separated lowercase naming conventions.
 * These names are translated to snake_case with appropriate type suffixes (e.g. {@code _total})
 * by PrometheusMeterRegistry during export.
 */
public final class MetricNames {

    private MetricNames() {
        // Prevent instantiation of constants class
    }

    // --- Migration Lifecycle Metrics ---

    /**
     * Counter: Total number of migration jobs initiated.
     * Exported to Prometheus as: {@code migration_started_total}
     */
    public static final String MIGRATION_STARTED = "migration.started";

    /**
     * Counter: Total number of migration jobs that completed successfully.
     * Exported to Prometheus as: {@code migration_completed_total}
     */
    public static final String MIGRATION_COMPLETED = "migration.completed";

    /**
     * Counter: Total number of migration jobs that failed.
     * Exported to Prometheus as: {@code migration_failed_total}
     */
    public static final String MIGRATION_FAILED = "migration.failed";

    /**
     * Timer: Total duration of migration executions from initiation to completion.
     * Exported to Prometheus as: {@code migration_duration_seconds} (summary/histogram)
     */
    public static final String MIGRATION_DURATION = "migration.duration";

    // --- Track Matching Metrics ---

    /**
     * Counter: Total number of tracks successfully matched.
     * Exported to Prometheus as: {@code tracks_matched_total}
     */
    public static final String TRACKS_MATCHED = "tracks.matched";

    /**
     * Counter: Total number of tracks that could not be matched.
     * Exported to Prometheus as: {@code tracks_unmatched_total}
     */
    public static final String TRACKS_UNMATCHED = "tracks.unmatched";

    /**
     * DistributionSummary: Statistical distribution of track match confidence scores (0.0 to 1.0).
     * Exposes count and sum in Prometheus as: {@code matching_score_count} and {@code matching_score_sum}.
     * The average match score is derived via: {@code matching_score_sum / matching_score_count}.
     */
    public static final String MATCHING_SCORE = "matching.score";

    // --- External Provider Metrics ---

    /**
     * Counter: Total number of outbound YouTube API requests.
     * Exported to Prometheus as: {@code youtube_api_requests_total}
     */
    public static final String YOUTUBE_API_REQUESTS = "youtube.api.requests";

    /**
     * Counter: Total number of outbound YouTube API errors encountered.
     * Exported to Prometheus as: {@code youtube_api_errors_total}
     */
    public static final String YOUTUBE_API_ERRORS = "youtube.api.errors";

    /**
     * Counter: Total number of outbound Spotify API requests.
     * Exported to Prometheus as: {@code spotify_api_requests_total}
     */
    public static final String SPOTIFY_API_REQUESTS = "spotify.api.requests";

    /**
     * Counter: Total number of outbound Spotify API errors encountered.
     * Exported to Prometheus as: {@code spotify_api_errors_total}
     */
    public static final String SPOTIFY_API_ERRORS = "spotify.api.errors";

    /**
     * Timer: Total duration of outbound Spotify API requests.
     * Exported to Prometheus as: {@code spotify_api_latency_seconds}
     */
    public static final String SPOTIFY_API_LATENCY = "spotify.api.latency";

    /**
     * Timer: Total duration of outbound YouTube API requests.
     * Exported to Prometheus as: {@code youtube_api_latency_seconds}
     */
    public static final String YOUTUBE_API_LATENCY = "youtube.api.latency";

    // --- Roadmap Mapping ---

    private static final Map<String, String> ROADMAP_TO_MICROMETER;
    private static final Map<String, String> MICROMETER_TO_PROMETHEUS;

    static {
        Map<String, String> roadmapMap = new LinkedHashMap<>();
        roadmapMap.put("migration_started_total", MIGRATION_STARTED);
        roadmapMap.put("migration_completed_total", MIGRATION_COMPLETED);
        roadmapMap.put("migration_failed_total", MIGRATION_FAILED);
        roadmapMap.put("tracks_matched_total", TRACKS_MATCHED);
        roadmapMap.put("tracks_unmatched_total", TRACKS_UNMATCHED);
        roadmapMap.put("youtube_api_requests_total", YOUTUBE_API_REQUESTS);
        roadmapMap.put("youtube_api_errors_total", YOUTUBE_API_ERRORS);
        roadmapMap.put("average_match_score", MATCHING_SCORE);
        roadmapMap.put("migration_duration", MIGRATION_DURATION);
        ROADMAP_TO_MICROMETER = Collections.unmodifiableMap(roadmapMap);

        Map<String, String> promMap = new LinkedHashMap<>();
        promMap.put(MIGRATION_STARTED, "migration_started_total");
        promMap.put(MIGRATION_COMPLETED, "migration_completed_total");
        promMap.put(MIGRATION_FAILED, "migration_failed_total");
        promMap.put(TRACKS_MATCHED, "tracks_matched_total");
        promMap.put(TRACKS_UNMATCHED, "tracks_unmatched_total");
        promMap.put(YOUTUBE_API_REQUESTS, "youtube_api_requests_total");
        promMap.put(YOUTUBE_API_ERRORS, "youtube_api_errors_total");
        promMap.put(SPOTIFY_API_REQUESTS, "spotify_api_requests_total");
        promMap.put(SPOTIFY_API_ERRORS, "spotify_api_errors_total");
        promMap.put(SPOTIFY_API_LATENCY, "spotify_api_latency_seconds");
        promMap.put(YOUTUBE_API_LATENCY, "youtube_api_latency_seconds");
        promMap.put(MATCHING_SCORE, "matching_score");
        promMap.put(MIGRATION_DURATION, "migration_duration_seconds");
        MICROMETER_TO_PROMETHEUS = Collections.unmodifiableMap(promMap);
    }

    /**
     * Returns an unmodifiable map of original Roadmap metric names to internal Micrometer names.
     */
    public static Map<String, String> getRoadmapMapping() {
        return ROADMAP_TO_MICROMETER;
    }

    /**
     * Returns the expected base Prometheus exported metric name for a given Micrometer metric name.
     */
    public static String toPrometheusBaseName(String micrometerName) {
        return MICROMETER_TO_PROMETHEUS.get(micrometerName);
    }
}
