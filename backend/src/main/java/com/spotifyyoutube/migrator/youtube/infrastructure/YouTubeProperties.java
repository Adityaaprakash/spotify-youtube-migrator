package com.spotifyyoutube.migrator.youtube.infrastructure;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "youtube")
public class YouTubeProperties {

    private String apiKey;
    private String baseUrl = "https://www.googleapis.com/youtube/v3";
    private int connectTimeoutMs = 5000;
    private int readTimeoutMs = 10000;
    private int maxResultsLimit = 50;
    private int defaultMaxResults = 25;

    public String getApiKey() {
        return apiKey;
    }

    public void setApiKey(String apiKey) {
        this.apiKey = apiKey;
    }

    public String getBaseUrl() {
        return baseUrl;
    }

    public void setBaseUrl(String baseUrl) {
        this.baseUrl = baseUrl;
    }

    public int getConnectTimeoutMs() {
        return connectTimeoutMs;
    }

    public void setConnectTimeoutMs(int connectTimeoutMs) {
        this.connectTimeoutMs = connectTimeoutMs;
    }

    public int getReadTimeoutMs() {
        return readTimeoutMs;
    }

    public void setReadTimeoutMs(int readTimeoutMs) {
        this.readTimeoutMs = readTimeoutMs;
    }

    public int getMaxResultsLimit() {
        return maxResultsLimit;
    }

    public void setMaxResultsLimit(int maxResultsLimit) {
        this.maxResultsLimit = maxResultsLimit;
    }

    public int getDefaultMaxResults() {
        return defaultMaxResults;
    }

    public void setDefaultMaxResults(int defaultMaxResults) {
        this.defaultMaxResults = defaultMaxResults;
    }
}
