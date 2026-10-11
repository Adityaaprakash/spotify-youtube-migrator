package com.spotifyyoutube.migrator.youtube.api.dto;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

public enum YouTubePrivacyStatus {
    PRIVATE("private"),
    UNLISTED("unlisted"),
    PUBLIC("public");

    private final String apiValue;

    YouTubePrivacyStatus(String apiValue) {
        this.apiValue = apiValue;
    }

    @JsonValue
    public String getApiValue() {
        return apiValue;
    }

    @JsonCreator
    public static YouTubePrivacyStatus fromValue(String value) {
        if (value == null || value.isBlank()) {
            return PRIVATE;
        }
        for (YouTubePrivacyStatus status : values()) {
            if (status.name().equalsIgnoreCase(value) || status.apiValue.equalsIgnoreCase(value)) {
                return status;
            }
        }
        throw new IllegalArgumentException("Invalid privacy status: " + value + ". Must be one of: PRIVATE, UNLISTED, PUBLIC");
    }
}
