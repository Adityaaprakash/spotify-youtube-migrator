package com.spotifyyoutube.migrator.matching.engine.feature;

import com.spotifyyoutube.migrator.migration.application.destination.model.DestinationTrackCandidate;

import java.time.Duration;
import java.util.Objects;
import java.util.Optional;

/**
 * Immutable container for comparison-ready features extracted from a {@link DestinationTrackCandidate}.
 * Normalizes title and channel representations, extracts duration in millisecond precision if present,
 * and encapsulates version/content evidence and official-signal indicators.
 *
 * @param candidateId unique candidate identifier (e.g. YouTube video ID)
 * @param rawTitle original candidate title as returned by the destination provider
 * @param normalizedTitle normalized candidate title
 * @param cleanTitle candidate title stripped of promotional and variant markers
 * @param channelOrArtist candidate channel or uploader name
 * @param normalizedChannelOrArtist normalized channel/artist name
 * @param duration candidate duration, or null if absent/unavailable
 * @param durationMs duration in milliseconds, or null if duration is absent
 * @param hasDuration whether candidate duration is available
 * @param hasDescription whether description is present and non-blank
 * @param description candidate description, or null if absent
 * @param publishedAt ISO-8601 publication timestamp, or null if absent
 * @param versionEvidence structured evidence of version/content indicators
 * @param officialSignal conservative evidence of official provenance
 * @param candidate underlying original candidate record
 */
public record CandidateTrackFeatures(
        String candidateId,
        String rawTitle,
        String normalizedTitle,
        String cleanTitle,
        String channelOrArtist,
        String normalizedChannelOrArtist,
        Duration duration,
        Long durationMs,
        boolean hasDuration,
        boolean hasDescription,
        String description,
        String publishedAt,
        CandidateVersionEvidence versionEvidence,
        OfficialSignalEvidence officialSignal,
        DestinationTrackCandidate candidate
) {
    public CandidateTrackFeatures {
        Objects.requireNonNull(candidateId, "candidateId must not be null");
        Objects.requireNonNull(candidate, "DestinationTrackCandidate must not be null");
        rawTitle = rawTitle != null ? rawTitle : "";
        normalizedTitle = normalizedTitle != null ? normalizedTitle : "";
        cleanTitle = cleanTitle != null ? cleanTitle : "";
        channelOrArtist = channelOrArtist != null ? channelOrArtist : "";
        normalizedChannelOrArtist = normalizedChannelOrArtist != null ? normalizedChannelOrArtist : "";
        versionEvidence = versionEvidence != null ? versionEvidence : CandidateVersionEvidence.empty();
        officialSignal = officialSignal != null ? officialSignal : OfficialSignalEvidence.none();
    }

    public Optional<Duration> getDuration() {
        return Optional.ofNullable(duration);
    }

    public Optional<Long> getDurationMs() {
        return Optional.ofNullable(durationMs);
    }

    public Optional<String> getDescription() {
        return Optional.ofNullable(description);
    }

    public Optional<String> getPublishedAt() {
        return Optional.ofNullable(publishedAt);
    }

    public boolean hasIndicator(VersionIndicator indicator) {
        return versionEvidence.hasIndicator(indicator);
    }
}
