package com.spotifyyoutube.migrator.migration.review.application;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Configuration properties governing manual candidate search limits, pagination bounds,
 * and external API quota safeguards for review operations.
 */
@Component
@ConfigurationProperties(prefix = "review.search")
public class ReviewSearchProperties {

    private int defaultMaxResults = 10;
    private int maxResultsLimit = 50;
    private int minResultsLimit = 1;
    private int maxQueriesPerTrack = 2;
    private int maxQueryLength = 500;

    public int getDefaultMaxResults() {
        return defaultMaxResults;
    }

    public void setDefaultMaxResults(int defaultMaxResults) {
        this.defaultMaxResults = defaultMaxResults;
    }

    public int getMaxResultsLimit() {
        return maxResultsLimit;
    }

    public void setMaxResultsLimit(int maxResultsLimit) {
        this.maxResultsLimit = maxResultsLimit;
    }

    public int getMinResultsLimit() {
        return minResultsLimit;
    }

    public void setMinResultsLimit(int minResultsLimit) {
        this.minResultsLimit = minResultsLimit;
    }

    public int getMaxQueriesPerTrack() {
        return maxQueriesPerTrack;
    }

    public void setMaxQueriesPerTrack(int maxQueriesPerTrack) {
        this.maxQueriesPerTrack = maxQueriesPerTrack;
    }

    public int getMaxQueryLength() {
        return maxQueryLength;
    }

    public void setMaxQueryLength(int maxQueryLength) {
        this.maxQueryLength = maxQueryLength;
    }
}
