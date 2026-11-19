package com.spotifyyoutube.migrator.common.metrics;

import java.util.Collections;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/**
 * Standard tag key constants and cardinality classification rules.
 *
 * <p>Enforces low-cardinality time-series boundaries. Prohibits dynamic, user-specific,
 * or payload-specific attributes that would lead to exponential time-series explosion.
 */
public final class TagKeys {

    private TagKeys() {
        // Prevent instantiation of constants class
    }

    // --- Permitted Bounded Tag Keys ---

    /**
     * External service provider (e.g. {@code spotify}, {@code youtube}).
     */
    public static final String PROVIDER = "provider";

    /**
     * Granular operation name (e.g. {@code search}, {@code create_playlist}, {@code insert_track}).
     */
    public static final String OPERATION = "operation";

    /**
     * Operational execution status (e.g. {@code success}, {@code error}, {@code rate_limited}).
     */
    public static final String STATUS = "status";

    /**
     * Terminal outcome of a migration or workflow (e.g. {@code completed}, {@code partially_completed}, {@code failed}).
     */
    public static final String OUTCOME = "outcome";

    /**
     * Match classification decision (e.g. {@code matched}, {@code unmatched}, {@code review_required}).
     */
    public static final String DECISION = "decision";

    /**
     * Discrete match confidence tier (e.g. {@code high}, {@code medium}, {@code low}).
     */
    public static final String CONFIDENCE = "confidence";

    /**
     * High-level categorized error class (e.g. {@code rate_limit}, {@code quota_exceeded}, {@code network_error}).
     */
    public static final String ERROR_CATEGORY = "error_category";

    /**
     * Execution trigger mode (e.g. {@code manual}, {@code automated}).
     */
    public static final String TRIGGER_MODE = "mode";

    // --- Prohibited High-Cardinality Keys ---

    private static final Set<String> PROHIBITED_KEYS;

    static {
        Set<String> set = new HashSet<>();
        set.add("userid");
        set.add("user_id");
        set.add("playlistid");
        set.add("playlist_id");
        set.add("trackid");
        set.add("track_id");
        set.add("title");
        set.add("track_title");
        set.add("artist");
        set.add("artist_name");
        set.add("url");
        set.add("raw_url");
        set.add("query");
        set.add("search_query");
        set.add("requestid");
        set.add("request_id");
        set.add("correlationid");
        set.add("correlation_id");
        set.add("traceid");
        set.add("trace_id");
        set.add("executionid");
        set.add("execution_id");
        set.add("exceptionmessage");
        set.add("exception_message");
        set.add("errormessage");
        set.add("error_message");
        set.add("message");
        PROHIBITED_KEYS = Collections.unmodifiableSet(set);
    }

    /**
     * Checks if a given tag key is in the prohibited high-cardinality blacklist.
     *
     * @param key the tag key candidate
     * @return {@code true} if the key is prohibited; {@code false} otherwise
     */
    public static boolean isProhibited(String key) {
        if (key == null) {
            return false;
        }
        return PROHIBITED_KEYS.contains(key.trim().toLowerCase(Locale.ROOT));
    }
}
