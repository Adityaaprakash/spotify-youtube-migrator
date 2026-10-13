package com.spotifyyoutube.migrator.matching.engine.feature;

import com.spotifyyoutube.migrator.matching.normalization.ArtistNormalizer;
import com.spotifyyoutube.migrator.matching.normalization.MediaFormat;
import com.spotifyyoutube.migrator.matching.normalization.MetadataTokenizer;
import com.spotifyyoutube.migrator.matching.normalization.NormalizedTrackMetadata;
import com.spotifyyoutube.migrator.matching.normalization.TitleNormalizer;
import com.spotifyyoutube.migrator.matching.normalization.TokenCategory;
import com.spotifyyoutube.migrator.matching.normalization.TokenizedTitle;
import com.spotifyyoutube.migrator.matching.normalization.VariantType;
import com.spotifyyoutube.migrator.migration.application.destination.model.DestinationTrackCandidate;
import org.springframework.stereotype.Component;

import java.text.Normalizer;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Default implementation of {@link TrackFeatureExtractor}.
 * Deterministically extracts comparison features from normalized source metadata and destination candidates.
 * Reuses Phase 5 normalization components without duplicating normalization or tokenization algorithms.
 */
@Component
public class DefaultTrackFeatureExtractor implements TrackFeatureExtractor {

    private static final Pattern BRACKET_PATTERN = Pattern.compile("[\\[\\(\\{]([^\\[\\(\\{\\]\\)\\}]*)[\\]\\)\\}]");
    private static final Pattern TRAILING_SEPARATOR_PATTERN = Pattern.compile("(?i)\\s*[-|/]\\s*([^-|/]+)$");

    // Reaction video patterns
    private static final Pattern REACTION_ANNOTATION_PATTERN = Pattern.compile(
            "(?i)^(?!.*\\b(?:chain|chemical|nuclear|allergic)\\b).*?(?:\\b(?:reaction|reacting|reacts)\\b|first\\s+time\\s+hearing).*?$"
    );
    private static final Pattern REACTION_TRAILING_PATTERN = Pattern.compile(
            "(?i)^(?!.*\\b(?:chain|chemical|nuclear|allergic)\\b).*?(?:\\b(?:reaction|reacting|reacts)(?:\\s+video)?|first\\s+time\\s+hearing).*?$"
    );
    private static final Pattern REACTION_PREFIX_PATTERN = Pattern.compile(
            "(?i)^(?:reaction|reacting|reacts)\\s*[-|:]\\s*.+$"
    );

    // Cover patterns
    private static final Pattern COVER_ANNOTATION_PATTERN = Pattern.compile(
            "(?i)^.*?(?:\\bcover(?:\\s+version)?|acoustic\\s+cover|drum\\s+cover|guitar\\s+cover|piano\\s+cover|vocal\\s+cover)\\b.*?$"
    );
    private static final Pattern COVER_TRAILING_PATTERN = Pattern.compile(
            "(?i)^.*?(?:\\bcover(?:\\s+version)?|acoustic\\s+cover|drum\\s+cover|guitar\\s+cover|piano\\s+cover|vocal\\s+cover)$"
    );
    private static final Pattern COVER_PREFIX_PATTERN = Pattern.compile("(?i)^cover\\s+by\\s+.+$");

    // Live patterns
    private static final Pattern LIVE_ANNOTATION_PATTERN = Pattern.compile(
            "(?i)^(?!.*\\blive\\s+(?:to\\s+tell|and\\s+let\\s+die|forever)\\b).*?\\blive\\b.*?$"
    );
    private static final Pattern LIVE_TRAILING_PATTERN = Pattern.compile(
            "(?i)^(?:live(?:\\s+(?:version|recording|session|audio|performance))?|live\\s+(?:at|in|from)\\s+.+)$"
    );

    // Remix patterns
    private static final Pattern REMIX_ANNOTATION_PATTERN = Pattern.compile("(?i)^.*?\\bremix(?:\\s+(?:version|edit|cut))?$");
    private static final Pattern REMIX_TRAILING_PATTERN = Pattern.compile("(?i)^.*?\\bremix(?:\\s+(?:version|edit|cut))?$");

    // Karaoke patterns
    private static final Pattern KARAOKE_ANNOTATION_PATTERN = Pattern.compile(
            "(?i)^.*?\\bkaraoke(?:\\s+(?:version|instrumental|track))?.*?$"
    );
    private static final Pattern KARAOKE_TRAILING_PATTERN = Pattern.compile(
            "(?i)^.*?\\bkaraoke(?:\\s+(?:version|instrumental|track))?$"
    );

    // Nightcore patterns
    private static final Pattern NIGHTCORE_ANNOTATION_PATTERN = Pattern.compile("(?i)^.*?\\bnightcore(?:\\s+version)?.*?$");
    private static final Pattern NIGHTCORE_TRAILING_PATTERN = Pattern.compile("(?i)^.*?\\bnightcore(?:\\s+version)?$");
    private static final Pattern NIGHTCORE_PREFIX_PATTERN = Pattern.compile("(?i)^nightcore\\s*[-|:]\\s*.+$");

    // Sped-up patterns
    private static final Pattern SPED_UP_ANNOTATION_PATTERN = Pattern.compile(
            "(?i)^(?:sped\\s+up|speed\\s+up|speeded\\s+up|accelerated)(?:\\s+version)?$"
    );
    private static final Pattern SPED_UP_TRAILING_PATTERN = Pattern.compile(
            "(?i)^(?:sped\\s+up|speed\\s+up|speeded\\s+up|accelerated)(?:\\s+version)?$"
    );

    // Slowed patterns
    private static final Pattern SLOWED_ANNOTATION_PATTERN = Pattern.compile(
            "(?i)^(?:slowed(?:\\s+down)?(?:\\s*(?:\\+|&|and)\\s*reverb)?(?:\\s+version)?|chopped\\s+(?:\\+|&|and)\\s+screwed|chopped\\s+and\\s+slowed)$"
    );
    private static final Pattern SLOWED_TRAILING_PATTERN = Pattern.compile(
            "(?i)^(?:slowed(?:\\s+down)?(?:\\s*(?:\\+|&|and)\\s*reverb)?(?:\\s+version)?|chopped\\s+(?:\\+|&|and)\\s+screwed|chopped\\s+and\\s+slowed)$"
    );

    // Acoustic patterns
    private static final Pattern ACOUSTIC_ANNOTATION_PATTERN = Pattern.compile(
            "(?i)^.*?\\b(?:acoustic(?:\\s+version)?|(?:mtv\\s+)?unplugged)\\b.*?$"
    );
    private static final Pattern ACOUSTIC_TRAILING_PATTERN = Pattern.compile(
            "(?i)^(?:acoustic(?:\\s+version)?|(?:mtv\\s+)?unplugged)$"
    );

    // Instrumental patterns
    private static final Pattern INSTRUMENTAL_ANNOTATION_PATTERN = Pattern.compile(
            "(?i)^.*?\\binstrumental(?:\\s+(?:version|mix))?\\b.*?$"
    );
    private static final Pattern INSTRUMENTAL_TRAILING_PATTERN = Pattern.compile(
            "(?i)^instrumental(?:\\s+(?:version|mix))?$"
    );

    private final TitleNormalizer titleNormalizer;
    private final ArtistNormalizer artistNormalizer;
    private final MetadataTokenizer metadataTokenizer;

    public DefaultTrackFeatureExtractor(
            TitleNormalizer titleNormalizer,
            ArtistNormalizer artistNormalizer,
            MetadataTokenizer metadataTokenizer
    ) {
        this.titleNormalizer = Objects.requireNonNull(titleNormalizer, "TitleNormalizer must not be null");
        this.artistNormalizer = Objects.requireNonNull(artistNormalizer, "ArtistNormalizer must not be null");
        this.metadataTokenizer = Objects.requireNonNull(metadataTokenizer, "MetadataTokenizer must not be null");
    }

    public DefaultTrackFeatureExtractor() {
        this(new TitleNormalizer(), new ArtistNormalizer(), new MetadataTokenizer());
    }

    @Override
    public SourceTrackFeatures extractSourceFeatures(NormalizedTrackMetadata sourceTrack) {
        return extractSourceFeatures(null, sourceTrack);
    }

    @Override
    public SourceTrackFeatures extractSourceFeatures(String sourceTrackId, NormalizedTrackMetadata sourceTrack) {
        Objects.requireNonNull(sourceTrack, "NormalizedTrackMetadata must not be null");

        String normalizedTitle = sourceTrack.normalizedTitle();
        String cleanTitle = sourceTrack.cleanTitle();
        String primaryArtist = sourceTrack.primaryArtist();
        List<String> normalizedArtists = sourceTrack.normalizedArtists();
        List<String> featuredArtists = sourceTrack.featuredArtists();
        String normalizedAlbum = sourceTrack.normalizedAlbum();
        Duration duration = sourceTrack.duration();
        Long durationMs = duration != null ? duration.toMillis() : null;

        boolean hasAlbum = normalizedAlbum != null && !normalizedAlbum.isBlank();
        boolean hasDuration = duration != null;
        boolean hasPrimaryArtist = primaryArtist != null && !primaryArtist.isBlank();

        Set<VersionIndicator> versionIndicators = mapVariantTypes(sourceTrack.variants());

        return new SourceTrackFeatures(
                sourceTrackId,
                normalizedTitle,
                cleanTitle,
                primaryArtist,
                normalizedArtists,
                featuredArtists,
                normalizedAlbum,
                duration,
                durationMs,
                hasAlbum,
                hasDuration,
                hasPrimaryArtist,
                versionIndicators,
                sourceTrack.isClean(),
                sourceTrack.isLive(),
                sourceTrack.isRemaster(),
                sourceTrack.isAcoustic(),
                sourceTrack
        );
    }

    @Override
    public CandidateTrackFeatures extractCandidateFeatures(DestinationTrackCandidate candidate) {
        return extractCandidateFeatures(candidate, null);
    }

    @Override
    public CandidateTrackFeatures extractCandidateFeatures(
            DestinationTrackCandidate candidate,
            NormalizedTrackMetadata sourceTrack
    ) {
        Objects.requireNonNull(candidate, "DestinationTrackCandidate must not be null");
        if (candidate.id() == null || candidate.id().isBlank()) {
            throw new IllegalArgumentException("DestinationTrackCandidate id must not be null or blank");
        }

        String candidateId = candidate.id().trim();
        String rawTitle = candidate.title() != null ? candidate.title() : "";
        String normalizedTitle = titleNormalizer.normalize(rawTitle);

        TokenizedTitle tokenized = metadataTokenizer.tokenize(rawTitle);
        String cleanTitle = tokenized.cleanTitle() != null ? tokenized.cleanTitle() : "";

        CandidateVersionEvidence versionEvidence = extractVersionEvidence(rawTitle, tokenized);

        String channelOrArtist = candidate.channelOrArtist() != null ? candidate.channelOrArtist().trim() : "";
        String normalizedChannelOrArtist = artistNormalizer.normalize(channelOrArtist);

        Duration duration = candidate.duration();
        Long durationMs = duration != null ? duration.toMillis() : null;
        boolean hasDuration = duration != null;

        String description = candidate.description();
        boolean hasDescription = description != null && !description.isBlank();
        String publishedAt = candidate.publishedAt();

        boolean hasOfficialAudio = tokenized.isOfficial() && (
                tokenized.mediaFormats().contains(MediaFormat.AUDIO)
                || tokenized.mediaFormats().contains(MediaFormat.VISUALIZER)
                || tokenized.tokens().stream().anyMatch(t ->
                        t.category() == TokenCategory.MEDIA_FORMAT
                        && ("audio".equalsIgnoreCase(t.value()) || "visualizer".equalsIgnoreCase(t.value()))
                )
        );
        boolean hasOfficialVideo = tokenized.isOfficial() && (
                tokenized.mediaFormats().contains(MediaFormat.VIDEO)
                || tokenized.mediaFormats().contains(MediaFormat.MUSIC_VIDEO)
                || tokenized.tokens().stream().anyMatch(t ->
                        t.category() == TokenCategory.MEDIA_FORMAT
                        && ("video".equalsIgnoreCase(t.value()) || "music_video".equalsIgnoreCase(t.value()))
                )
        );

        String sourcePrimaryArtist = sourceTrack != null ? sourceTrack.primaryArtist() : null;
        OfficialSignalEvidence officialSignal = OfficialSignalEvidence.from(
                candidate,
                sourcePrimaryArtist,
                hasOfficialAudio,
                hasOfficialVideo
        );

        return new CandidateTrackFeatures(
                candidateId,
                rawTitle,
                normalizedTitle,
                cleanTitle,
                channelOrArtist,
                normalizedChannelOrArtist,
                duration,
                durationMs,
                hasDuration,
                hasDescription,
                description,
                publishedAt,
                versionEvidence,
                officialSignal,
                candidate
        );
    }

    @Override
    public List<CandidateTrackFeatures> extractCandidateFeatures(
            List<DestinationTrackCandidate> candidates,
            NormalizedTrackMetadata sourceTrack
    ) {
        if (candidates == null || candidates.isEmpty()) {
            return List.of();
        }
        List<CandidateTrackFeatures> results = new ArrayList<>(candidates.size());
        for (DestinationTrackCandidate c : candidates) {
            if (c != null && c.id() != null && !c.id().isBlank()) {
                results.add(extractCandidateFeatures(c, sourceTrack));
            }
        }
        return Collections.unmodifiableList(results);
    }

    private CandidateVersionEvidence extractVersionEvidence(String rawTitle, TokenizedTitle tokenized) {
        if (rawTitle == null || rawTitle.isBlank()) {
            return CandidateVersionEvidence.empty();
        }

        String nfc = Normalizer.normalize(rawTitle, Normalizer.Form.NFC);
        Set<VersionIndicator> indicators = new LinkedHashSet<>();
        List<String> rawTokens = new ArrayList<>();
        List<String> unmatchedAnnotations = new ArrayList<>();

        // 1. Seed from metadata tokenizer variants
        if (tokenized != null && tokenized.variants() != null) {
            for (VariantType variant : tokenized.variants()) {
                VersionIndicator indicator = mapSingleVariantType(variant);
                if (indicator != null) {
                    indicators.add(indicator);
                }
            }
        }

        // 2. Scan bracketed annotations: (...), [...], {...}
        Matcher bracketMatcher = BRACKET_PATTERN.matcher(nfc);
        while (bracketMatcher.find()) {
            String inner = bracketMatcher.group(1).trim();
            if (inner.isBlank()) {
                continue;
            }
            boolean matched = inspectSegmentForIndicators(inner, indicators, rawTokens, true);
            if (!matched && !isKnownNonVersionAnnotation(inner)) {
                unmatchedAnnotations.add(inner);
            }
        }

        // 3. Scan trailing delimited segments: " - Live at Wembley", " | Cover", " - Reaction"
        Matcher trailingMatcher = TRAILING_SEPARATOR_PATTERN.matcher(nfc);
        if (trailingMatcher.find()) {
            String trailing = trailingMatcher.group(1).trim();
            if (!trailing.isBlank()) {
                inspectSegmentForIndicators(trailing, indicators, rawTokens, false);
            }
        }

        // 4. Scan prefix indicators: "Nightcore - ...", "Cover by ...", "Reaction - ..."
        if (NIGHTCORE_PREFIX_PATTERN.matcher(nfc).matches()) {
            indicators.add(VersionIndicator.NIGHTCORE);
            rawTokens.add("Nightcore");
        }
        if (COVER_PREFIX_PATTERN.matcher(nfc).matches()) {
            indicators.add(VersionIndicator.COVER);
            rawTokens.add("Cover by");
        }
        if (REACTION_PREFIX_PATTERN.matcher(nfc).matches()) {
            indicators.add(VersionIndicator.REACTION);
            rawTokens.add("Reaction");
        }

        // 5. Conflict & Ambiguity evaluation
        boolean hasConflict = (indicators.contains(VersionIndicator.SPED_UP) && indicators.contains(VersionIndicator.SLOWED))
                || (indicators.contains(VersionIndicator.LIVE) && tokenized != null && tokenized.hasVariant(VariantType.STUDIO));
        boolean hasAmbiguity = indicators.size() >= 3 || hasConflict;

        return new CandidateVersionEvidence(
                indicators,
                rawTokens,
                unmatchedAnnotations,
                hasConflict,
                hasAmbiguity
        );
    }

    private boolean inspectSegmentForIndicators(
            String segment,
            Set<VersionIndicator> indicators,
            List<String> rawTokens,
            boolean isBracketed
    ) {
        boolean matched = false;

        // Reaction check
        Pattern reactionPattern = isBracketed ? REACTION_ANNOTATION_PATTERN : REACTION_TRAILING_PATTERN;
        if (reactionPattern.matcher(segment).matches()) {
            indicators.add(VersionIndicator.REACTION);
            rawTokens.add(segment);
            matched = true;
        }

        // Cover check
        Pattern coverPattern = isBracketed ? COVER_ANNOTATION_PATTERN : COVER_TRAILING_PATTERN;
        if (coverPattern.matcher(segment).matches()) {
            indicators.add(VersionIndicator.COVER);
            rawTokens.add(segment);
            matched = true;
        }

        // Live check
        Pattern livePattern = isBracketed ? LIVE_ANNOTATION_PATTERN : LIVE_TRAILING_PATTERN;
        if (livePattern.matcher(segment).matches()) {
            indicators.add(VersionIndicator.LIVE);
            rawTokens.add(segment);
            matched = true;
        }

        // Remix check
        Pattern remixPattern = isBracketed ? REMIX_ANNOTATION_PATTERN : REMIX_TRAILING_PATTERN;
        if (remixPattern.matcher(segment).matches()) {
            indicators.add(VersionIndicator.REMIX);
            rawTokens.add(segment);
            matched = true;
        }

        // Karaoke check
        Pattern karaokePattern = isBracketed ? KARAOKE_ANNOTATION_PATTERN : KARAOKE_TRAILING_PATTERN;
        if (karaokePattern.matcher(segment).matches()) {
            indicators.add(VersionIndicator.KARAOKE);
            rawTokens.add(segment);
            matched = true;
        }

        // Nightcore check
        Pattern nightcorePattern = isBracketed ? NIGHTCORE_ANNOTATION_PATTERN : NIGHTCORE_TRAILING_PATTERN;
        if (nightcorePattern.matcher(segment).matches()) {
            indicators.add(VersionIndicator.NIGHTCORE);
            rawTokens.add(segment);
            matched = true;
        }

        // Sped-up check
        Pattern spedUpPattern = isBracketed ? SPED_UP_ANNOTATION_PATTERN : SPED_UP_TRAILING_PATTERN;
        if (spedUpPattern.matcher(segment).matches()) {
            indicators.add(VersionIndicator.SPED_UP);
            rawTokens.add(segment);
            matched = true;
        }

        // Slowed check
        Pattern slowedPattern = isBracketed ? SLOWED_ANNOTATION_PATTERN : SLOWED_TRAILING_PATTERN;
        if (slowedPattern.matcher(segment).matches()) {
            indicators.add(VersionIndicator.SLOWED);
            rawTokens.add(segment);
            matched = true;
        }

        // Acoustic check
        Pattern acousticPattern = isBracketed ? ACOUSTIC_ANNOTATION_PATTERN : ACOUSTIC_TRAILING_PATTERN;
        if (acousticPattern.matcher(segment).matches()) {
            indicators.add(VersionIndicator.ACOUSTIC);
            rawTokens.add(segment);
            matched = true;
        }

        // Instrumental check
        Pattern instrumentalPattern = isBracketed ? INSTRUMENTAL_ANNOTATION_PATTERN : INSTRUMENTAL_TRAILING_PATTERN;
        if (instrumentalPattern.matcher(segment).matches()) {
            indicators.add(VersionIndicator.INSTRUMENTAL);
            rawTokens.add(segment);
            matched = true;
        }

        // Remaster check
        if (segment.toLowerCase(Locale.ROOT).contains("remaster")) {
            indicators.add(VersionIndicator.REMASTER);
            rawTokens.add(segment);
            matched = true;
        }

        return matched;
    }

    private boolean isKnownNonVersionAnnotation(String text) {
        String lower = text.toLowerCase(Locale.ROOT);
        return lower.startsWith("feat.")
                || lower.startsWith("ft.")
                || lower.startsWith("featuring")
                || lower.equals("official audio")
                || lower.equals("official video")
                || lower.equals("official music video")
                || lower.equals("official lyric video")
                || lower.equals("lyric video")
                || lower.equals("visualizer")
                || lower.equals("audio")
                || lower.equals("video");
    }

    private Set<VersionIndicator> mapVariantTypes(Set<VariantType> variants) {
        if (variants == null || variants.isEmpty()) {
            return Collections.emptySet();
        }
        Set<VersionIndicator> mapped = new LinkedHashSet<>();
        for (VariantType v : variants) {
            VersionIndicator indicator = mapSingleVariantType(v);
            if (indicator != null) {
                mapped.add(indicator);
            }
        }
        return Collections.unmodifiableSet(mapped);
    }

    private VersionIndicator mapSingleVariantType(VariantType type) {
        if (type == null) {
            return null;
        }
        return switch (type) {
            case COVER -> VersionIndicator.COVER;
            case LIVE -> VersionIndicator.LIVE;
            case REMIX -> VersionIndicator.REMIX;
            case KARAOKE -> VersionIndicator.KARAOKE;
            case NIGHTCORE -> VersionIndicator.NIGHTCORE;
            case SPED_UP -> VersionIndicator.SPED_UP;
            case SLOWED, SLOWED_REVERB -> VersionIndicator.SLOWED;
            case ACOUSTIC, UNPLUGGED -> VersionIndicator.ACOUSTIC;
            case INSTRUMENTAL -> VersionIndicator.INSTRUMENTAL;
            case REMASTER -> VersionIndicator.REMASTER;
            default -> null;
        };
    }
}
