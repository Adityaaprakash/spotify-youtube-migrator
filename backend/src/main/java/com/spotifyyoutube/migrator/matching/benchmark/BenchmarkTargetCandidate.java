package com.spotifyyoutube.migrator.matching.benchmark;

import java.util.Objects;
import java.util.Optional;

/**
 * Immutable representation of a ground-truth YouTube target candidate in a benchmark example.
 *
 * @param videoId non-null, non-blank expected YouTube video identifier (e.g. 11-character video ID)
 * @param title optional expected YouTube video title
 * @param channelTitle optional expected YouTube channel or uploader title
 * @param durationMs optional expected playback duration in milliseconds
 * @param channelId optional expected YouTube channel identifier
 * @param isOfficial optional indicator of whether the target represents verified official provenance
 */
public record BenchmarkTargetCandidate(
        String videoId,
        String title,
        String channelTitle,
        Long durationMs,
        String channelId,
        Boolean isOfficial
) {

    public BenchmarkTargetCandidate {
        Objects.requireNonNull(videoId, "videoId must not be null");
        if (videoId.isBlank()) {
            throw new IllegalArgumentException("videoId must not be blank");
        }
        videoId = videoId.trim();
        title = title != null ? title.trim() : null;
        channelTitle = channelTitle != null ? channelTitle.trim() : null;
        channelId = channelId != null ? channelId.trim() : null;
        if (durationMs != null && durationMs <= 0) {
            throw new IllegalArgumentException("durationMs must be positive: " + durationMs);
        }
    }

    /**
     * Convenience factory for standard target candidate creation.
     *
     * @param videoId YouTube video ID
     * @param title YouTube video title
     * @param channelTitle channel title
     * @param durationMs duration in milliseconds
     * @param isOfficial whether provenance is official
     * @return constructed BenchmarkTargetCandidate
     */
    public static BenchmarkTargetCandidate of(
            String videoId,
            String title,
            String channelTitle,
            Long durationMs,
            Boolean isOfficial
    ) {
        return new BenchmarkTargetCandidate(videoId, title, channelTitle, durationMs, null, isOfficial);
    }

    public Optional<String> getTitle() {
        return Optional.ofNullable(title);
    }

    public Optional<String> getChannelTitle() {
        return Optional.ofNullable(channelTitle);
    }

    public Optional<Long> getDurationMs() {
        return Optional.ofNullable(durationMs);
    }

    public Optional<String> getChannelId() {
        return Optional.ofNullable(channelId);
    }

    public Optional<Boolean> getIsOfficial() {
        return Optional.ofNullable(isOfficial);
    }
}
