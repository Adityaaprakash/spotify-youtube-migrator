package com.spotifyyoutube.migrator.matching.normalization;

import org.springframework.stereotype.Component;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Provider-independent metadata tokenizer that analyzes music metadata (primarily track titles)
 * to extract structured, immutable tokens and track-variant classifications.
 * <p>
 * Distinguishes between:
 * <ul>
 *   <li>Official-content markers (e.g. Official Audio, Official Video, Lyric Video)</li>
 *   <li>Media format indicators (audio vs. video vs. visualizer)</li>
 *   <li>Remaster information, including release/remaster year</li>
 *   <li>Edit and mix types (e.g. Radio Edit, Extended Mix, Club Mix, Instrumental, Demo)</li>
 *   <li>Performance and recording context (e.g. Live at Wembley, Acoustic, Unplugged, Studio)</li>
 *   <li>Derivative and alternate versions (e.g. Remix, Cover, Karaoke, Nightcore, Sped Up, Slowed + Reverb)</li>
 *   <li>Featured artist credit syntax (feat., ft., featuring) without misclassification</li>
 *   <li>Unknown parenthetical annotations (preserved for downstream matching)</li>
 * </ul>
 * <p>
 * This component is stateless, deterministic, pure, and free of external dependencies.
 */
@Component
public class MetadataTokenizer {

    private static final Pattern BRACKET_PATTERN = Pattern.compile("[\\[\\(\\{]([^\\[\\(\\{\\]\\)\\}]*)[\\]\\)\\}]");
    private static final Pattern MULTI_SPACE_PATTERN = Pattern.compile("\\s+");
    private static final Pattern DANGLING_SEPARATOR_PATTERN = Pattern.compile("^\\s*[-|/]+\\s*|\\s*[-|/]+\\s*$");

    // Featured artist marker within annotations (e.g. "feat. Artist", "ft. Artist")
    private static final Pattern FEATURED_ARTIST_PATTERN = Pattern.compile(
            "(?i)^\\s*(?:feat\\.?|ft\\.?|featuring)\\s+(.+)$"
    );

    // Separators between multiple featured artists within credit (e.g. "Guest 1, Guest 2 & Guest 3")
    private static final Pattern FEATURED_DELIMITER_PATTERN = Pattern.compile(
            "(?i)\\s*(?:,\\s*|\\s+&\\s+|\\s+and\\s+)"
    );

    // Unbracketed trailing featured credit: e.g. "Song feat. Guest", "Song ft Guest"
    private static final Pattern TRAILING_FEATURED_PATTERN = Pattern.compile(
            "(?i)\\s+(?:feat\\.?|ft\\.?|featuring)\\s+(.+)$"
    );

    // Sub-delimiter within parenthetical annotations (e.g. "Live, Remastered 2024" or "Official Video / Live")
    private static final Pattern INTERNAL_DELIMITER_PATTERN = Pattern.compile("[,;/|]");

    // Trailing separator matching e.g. "Title - Official Video" or "Title | Live at Wembley"
    private static final Pattern TRAILING_SEPARATOR_PATTERN = Pattern.compile(
            "(?i)\\s*[-|/]\\s*([^-|/]+)$"
    );

    // Official Content & Media Format patterns
    private static final Pattern OFFICIAL_MUSIC_VIDEO_PATTERN = Pattern.compile("(?i)^official\\s+music\\s+video$");
    private static final Pattern OFFICIAL_LYRIC_VIDEO_PATTERN = Pattern.compile("(?i)^official\\s+lyric[s]?\\s+video$");
    private static final Pattern LYRIC_VIDEO_PATTERN = Pattern.compile("(?i)^lyric[s]?\\s+video$");
    private static final Pattern MUSIC_VIDEO_PATTERN = Pattern.compile("(?i)^music\\s+video$");
    private static final Pattern OFFICIAL_VIDEO_PATTERN = Pattern.compile("(?i)^official\\s+video$");
    private static final Pattern OFFICIAL_AUDIO_PATTERN = Pattern.compile("(?i)^official\\s+audio$");
    private static final Pattern OFFICIAL_VISUALIZER_PATTERN = Pattern.compile("(?i)^official\\s+visualizer$");
    private static final Pattern VISUALIZER_PATTERN = Pattern.compile("(?i)^visualizer$");
    private static final Pattern OFFICIAL_ALONE_PATTERN = Pattern.compile("(?i)^official$");
    private static final Pattern VIDEO_ALONE_PATTERN = Pattern.compile("(?i)^video$");
    private static final Pattern AUDIO_ALONE_PATTERN = Pattern.compile("(?i)^audio$");

    // Remaster patterns (capturing leading or trailing 4-digit years)
    private static final Pattern REMASTER_FULL_PATTERN = Pattern.compile(
            "(?i)^(?:(\\d{4})\\s+)?(?:digital(?:ly)?\\s+|anniversary\\s+)?remaster(?:ed)?(?:"
                    + "\\s+(?:version|edition))?(?:\\s+(\\d{4}))?$"
    );

    // Edit and Mix patterns
    private static final Pattern RADIO_EDIT_PATTERN = Pattern.compile("(?i)^radio\\s+(?:edit|mix|version)$");
    private static final Pattern EXTENDED_MIX_PATTERN = Pattern.compile("(?i)^extended\\s+(?:mix|version|edit)$");
    private static final Pattern ORIGINAL_MIX_PATTERN = Pattern.compile("(?i)^original\\s+(?:mix|version)$");
    private static final Pattern CLUB_MIX_PATTERN = Pattern.compile("(?i)^club\\s+(?:mix|edit|version)$");
    private static final Pattern INSTRUMENTAL_PATTERN = Pattern.compile("(?i)^instrumental(?:\\s+(?:version|mix))?$");
    private static final Pattern DEMO_PATTERN = Pattern.compile("(?i)^demo(?:\\s+(?:version|mix))?$");

    // Performance and Recording patterns
    private static final Pattern LIVE_VENUE_PATTERN = Pattern.compile("(?i)^live\\s+(?:at|in|from)\\s+(.+)$");
    private static final Pattern LIVE_PLAIN_PATTERN = Pattern.compile("(?i)^live(?:\\s+version)?$");
    private static final Pattern ACOUSTIC_PATTERN = Pattern.compile("(?i)^acoustic(?:\\s+(?:version|mix))?$");
    private static final Pattern UNPLUGGED_PATTERN = Pattern.compile("(?i)^(?:mtv\\s+)?unplugged$");
    private static final Pattern STUDIO_PATTERN = Pattern.compile("(?i)^studio\\s+(?:version|mix|recording)$");

    // Derivative and Alternate patterns
    private static final Pattern SLOWED_REVERB_PATTERN = Pattern.compile(
            "(?i)^slowed(?:\\s+down)?\\s*(?:\\+|\\&|and)\\s*reverb(?:\\s+version)?$"
    );
    private static final Pattern SLOWED_PATTERN = Pattern.compile("(?i)^slowed(?:\\s+down)?(?:\\s+version)?$");
    private static final Pattern SPED_UP_PATTERN = Pattern.compile(
            "(?i)^(?:sped\\s+up|speed\\s+up|speeded\\s+up)(?:\\s+version)?$"
    );
    private static final Pattern NIGHTCORE_PATTERN = Pattern.compile("(?i)^nightcore(?:\\s+version)?$");
    private static final Pattern REVERB_PATTERN = Pattern.compile("(?i)^reverb(?:\\s+(?:version|mix))?$");
    private static final Pattern KARAOKE_PATTERN = Pattern.compile("(?i)^karaoke(?:\\s+(?:version|instrumental))?$");
    private static final Pattern COVER_PATTERN = Pattern.compile("(?i)^cover(?:\\s+version)?$");
    private static final Pattern REMIX_PATTERN = Pattern.compile("(?i)^(?:.*?\\s+)?remix(?:\\s+version)?$");

    /**
     * Analyzes raw track metadata (title) and returns an immutable structured {@link TokenizedTitle}.
     *
     * @param rawTitle raw title string, may be null or blank
     * @return structured tokenization result
     */
    public TokenizedTitle tokenize(String rawTitle) {
        if (rawTitle == null || rawTitle.isBlank()) {
            return new TokenizedTitle(
                    rawTitle != null ? rawTitle : "",
                    "",
                    false,
                    Set.of(),
                    null,
                    null,
                    Set.of(),
                    List.of(),
                    List.of(),
                    List.of()
            );
        }

        String normalizedUnicode = Normalizer.normalize(rawTitle, Normalizer.Form.NFC);

        boolean isOfficial = false;
        Set<MediaFormat> mediaFormats = new LinkedHashSet<>();
        Set<VariantType> variants = new LinkedHashSet<>();
        List<MetadataToken> tokens = new ArrayList<>();
        List<String> unknownAnnotations = new ArrayList<>();
        List<String> featuredArtists = new ArrayList<>();
        RemasterDetails remasterDetails = null;
        LivePerformanceDetails liveDetails = null;

        // Track text spans in rawTitle that are recognized and should be removed from cleanTitle
        List<IntRange> recognizedRanges = new ArrayList<>();

        // 1. Scan bracketed annotations: (...), [...], {...}
        Matcher bracketMatcher = BRACKET_PATTERN.matcher(normalizedUnicode);
        while (bracketMatcher.find()) {
            int start = bracketMatcher.start();
            int end = bracketMatcher.end();
            String innerText = bracketMatcher.group(1).trim();

            if (innerText.isEmpty() || innerText.replaceAll("[-|/\\s]", "").isEmpty()) {
                recognizedRanges.add(new IntRange(start, end));
                continue;
            }

            // Parse innerText as metadata annotation(s)
            AnnotationParseResult parsed = parseAnnotationSegment(innerText, bracketMatcher.group(0));
            if (parsed.recognized()) {
                if (parsed.isOfficial()) {
                    isOfficial = true;
                }
                mediaFormats.addAll(parsed.mediaFormats());
                variants.addAll(parsed.variants());
                tokens.addAll(parsed.tokens());
                featuredArtists.addAll(parsed.featuredArtists());
                if (parsed.remasterDetails() != null && remasterDetails == null) {
                    remasterDetails = parsed.remasterDetails();
                }
                if (parsed.liveDetails() != null && liveDetails == null) {
                    liveDetails = parsed.liveDetails();
                }
                recognizedRanges.add(new IntRange(start, end));
            } else {
                // Unrecognized bracketed text: preserve in unknownAnnotations
                unknownAnnotations.add(innerText);
                tokens.add(new MetadataToken(TokenCategory.UNKNOWN_ANNOTATION, innerText, bracketMatcher.group(0)));
                // Do NOT mark as recognized for removal so unknown text remains in cleanTitle
            }
        }

        // 2. Build intermediate title after removing recognized bracket ranges
        String intermediate = removeRanges(normalizedUnicode, recognizedRanges);

        // 3. Scan trailing separator annotations (e.g. " - Official Video", " | Live at Wembley")
        Matcher trailingMatcher = TRAILING_SEPARATOR_PATTERN.matcher(intermediate);
        if (trailingMatcher.find()) {
            String candidateTrailing = trailingMatcher.group(1).trim();
            String titleBefore = intermediate.substring(0, trailingMatcher.start()).trim();

            // Only recognize trailing separator annotation if substantive title remains before it
            if (!titleBefore.isEmpty()) {
                AnnotationParseResult parsedTrailing = parseAnnotationSegment(
                        candidateTrailing,
                        trailingMatcher.group(0).trim()
                );
                if (parsedTrailing.recognized()) {
                    if (parsedTrailing.isOfficial()) {
                        isOfficial = true;
                    }
                    mediaFormats.addAll(parsedTrailing.mediaFormats());
                    variants.addAll(parsedTrailing.variants());
                    tokens.addAll(parsedTrailing.tokens());
                    featuredArtists.addAll(parsedTrailing.featuredArtists());
                    if (parsedTrailing.remasterDetails() != null && remasterDetails == null) {
                        remasterDetails = parsedTrailing.remasterDetails();
                    }
                    if (parsedTrailing.liveDetails() != null && liveDetails == null) {
                        liveDetails = parsedTrailing.liveDetails();
                    }
                    intermediate = titleBefore;
                }
            }
        }

        // 4. Scan unbracketed trailing featured artist credit (e.g. "Song feat. DaBaby")
        Matcher trailingFeaturedMatcher = TRAILING_FEATURED_PATTERN.matcher(intermediate);
        if (trailingFeaturedMatcher.find()) {
            String titleBefore = intermediate.substring(0, trailingFeaturedMatcher.start()).trim();
            if (!titleBefore.isEmpty()) {
                String rawArtistGroup = trailingFeaturedMatcher.group(1).trim();
                String[] artists = FEATURED_DELIMITER_PATTERN.split(rawArtistGroup);
                for (String artist : artists) {
                    String trimmedArtist = artist.trim();
                    if (!trimmedArtist.isEmpty()) {
                        featuredArtists.add(trimmedArtist);
                        tokens.add(new MetadataToken(
                                TokenCategory.FEATURED_ARTIST,
                                trimmedArtist,
                                trailingFeaturedMatcher.group(0).trim()
                        ));
                    }
                }
                intermediate = titleBefore;
            }
        }

        // 5. Clean up title whitespace and dangling separators
        String cleanTitle = cleanupTitle(intermediate);

        // If aggressive cleaning completely erased the title, revert to original title trimmed
        if (cleanTitle.isBlank()) {
            cleanTitle = cleanupTitle(normalizedUnicode);
        }

        return new TokenizedTitle(
                rawTitle,
                cleanTitle,
                isOfficial,
                mediaFormats,
                remasterDetails,
                liveDetails,
                variants,
                tokens,
                unknownAnnotations,
                featuredArtists
        );
    }

    private AnnotationParseResult parseAnnotationSegment(String text, String rawSpan) {
        String trimmedText = text.trim();

        // If the entire segment is a featured artist credit (e.g. "feat. Artist 1, Artist 2 & Artist 3"),
        // handle it directly so comma separators between artist names aren't split prematurely
        Matcher wholeFeaturedMatcher = FEATURED_ARTIST_PATTERN.matcher(trimmedText);
        if (wholeFeaturedMatcher.matches()) {
            return matchSingleAnnotation(trimmedText, rawSpan);
        }

        // If segment contains multiple sub-annotations separated by comma, slash, semicolon, pipe:
        // check sub-annotations first so composite strings like "Live at Wembley, Remastered 2024"
        // don't get swallowed by greedy patterns.
        if (INTERNAL_DELIMITER_PATTERN.matcher(text).find()) {
            String[] subSegments = INTERNAL_DELIMITER_PATTERN.split(text);
            boolean anyRecognized = false;
            boolean isOfficial = false;
            Set<MediaFormat> formats = new LinkedHashSet<>();
            Set<VariantType> variants = new LinkedHashSet<>();
            List<MetadataToken> tokens = new ArrayList<>();
            List<String> featuredArtists = new ArrayList<>();
            RemasterDetails remaster = null;
            LivePerformanceDetails live = null;

            for (String sub : subSegments) {
                String subTrimmed = sub.trim();
                if (subTrimmed.isEmpty()) {
                    continue;
                }
                AnnotationParseResult subResult = matchSingleAnnotation(subTrimmed, subTrimmed);
                if (subResult.recognized()) {
                    anyRecognized = true;
                    if (subResult.isOfficial()) {
                        isOfficial = true;
                    }
                    formats.addAll(subResult.mediaFormats());
                    variants.addAll(subResult.variants());
                    tokens.addAll(subResult.tokens());
                    featuredArtists.addAll(subResult.featuredArtists());
                    if (subResult.remasterDetails() != null && remaster == null) {
                        remaster = subResult.remasterDetails();
                    }
                    if (subResult.liveDetails() != null && live == null) {
                        live = subResult.liveDetails();
                    }
                }
            }

            if (anyRecognized) {
                return new AnnotationParseResult(
                        true, isOfficial, formats, variants, tokens, remaster, live, featuredArtists
                );
            }
        }

        // Test if the entire segment matches as a whole (e.g. "Slowed + Reverb", "Official Music Video")
        return matchSingleAnnotation(text, rawSpan);
    }

    private AnnotationParseResult matchSingleAnnotation(String text, String rawSpan) {
        String trimmed = text.trim();

        // 0. Featured artist credit (e.g. "feat. Artist", "ft. Artist & Another")
        Matcher featuredMatcher = FEATURED_ARTIST_PATTERN.matcher(trimmed);
        if (featuredMatcher.matches()) {
            String rawArtistGroup = featuredMatcher.group(1).trim();
            String[] artists = FEATURED_DELIMITER_PATTERN.split(rawArtistGroup);
            List<String> parsedArtists = new ArrayList<>();
            List<MetadataToken> tokens = new ArrayList<>();
            for (String artist : artists) {
                String trimmedArtist = artist.trim();
                if (!trimmedArtist.isEmpty()) {
                    parsedArtists.add(trimmedArtist);
                    tokens.add(new MetadataToken(TokenCategory.FEATURED_ARTIST, trimmedArtist, rawSpan));
                }
            }
            return new AnnotationParseResult(
                    true, false, Set.of(), Set.of(), tokens, null, null, parsedArtists
            );
        }

        // A. Official & Media Formats
        if (OFFICIAL_MUSIC_VIDEO_PATTERN.matcher(trimmed).matches()) {
            List<MetadataToken> tokens = List.of(
                    new MetadataToken(TokenCategory.OFFICIAL_CONTENT, "official", rawSpan),
                    new MetadataToken(TokenCategory.MEDIA_FORMAT, "music_video", rawSpan)
            );
            return new AnnotationParseResult(true, true, Set.of(MediaFormat.MUSIC_VIDEO), Set.of(), tokens, null, null);
        }
        if (OFFICIAL_LYRIC_VIDEO_PATTERN.matcher(trimmed).matches()) {
            List<MetadataToken> tokens = List.of(
                    new MetadataToken(TokenCategory.OFFICIAL_CONTENT, "official", rawSpan),
                    new MetadataToken(TokenCategory.MEDIA_FORMAT, "lyric_video", rawSpan)
            );
            return new AnnotationParseResult(true, true, Set.of(MediaFormat.LYRIC_VIDEO), Set.of(), tokens, null, null);
        }
        if (LYRIC_VIDEO_PATTERN.matcher(trimmed).matches()) {
            List<MetadataToken> tokens = List.of(
                    new MetadataToken(TokenCategory.MEDIA_FORMAT, "lyric_video", rawSpan)
            );
            return new AnnotationParseResult(true, false, Set.of(MediaFormat.LYRIC_VIDEO), Set.of(), tokens, null, null);
        }
        if (MUSIC_VIDEO_PATTERN.matcher(trimmed).matches()) {
            List<MetadataToken> tokens = List.of(
                    new MetadataToken(TokenCategory.MEDIA_FORMAT, "music_video", rawSpan)
            );
            return new AnnotationParseResult(true, false, Set.of(MediaFormat.MUSIC_VIDEO), Set.of(), tokens, null, null);
        }
        if (OFFICIAL_VIDEO_PATTERN.matcher(trimmed).matches()) {
            List<MetadataToken> tokens = List.of(
                    new MetadataToken(TokenCategory.OFFICIAL_CONTENT, "official", rawSpan),
                    new MetadataToken(TokenCategory.MEDIA_FORMAT, "video", rawSpan)
            );
            return new AnnotationParseResult(true, true, Set.of(MediaFormat.VIDEO), Set.of(), tokens, null, null);
        }
        if (OFFICIAL_AUDIO_PATTERN.matcher(trimmed).matches()) {
            List<MetadataToken> tokens = List.of(
                    new MetadataToken(TokenCategory.OFFICIAL_CONTENT, "official", rawSpan),
                    new MetadataToken(TokenCategory.MEDIA_FORMAT, "audio", rawSpan)
            );
            return new AnnotationParseResult(true, true, Set.of(MediaFormat.AUDIO), Set.of(), tokens, null, null);
        }
        if (OFFICIAL_VISUALIZER_PATTERN.matcher(trimmed).matches()) {
            List<MetadataToken> tokens = List.of(
                    new MetadataToken(TokenCategory.OFFICIAL_CONTENT, "official", rawSpan),
                    new MetadataToken(TokenCategory.MEDIA_FORMAT, "visualizer", rawSpan)
            );
            return new AnnotationParseResult(true, true, Set.of(MediaFormat.VISUALIZER), Set.of(), tokens, null, null);
        }
        if (VISUALIZER_PATTERN.matcher(trimmed).matches()) {
            List<MetadataToken> tokens = List.of(
                    new MetadataToken(TokenCategory.MEDIA_FORMAT, "visualizer", rawSpan)
            );
            return new AnnotationParseResult(true, false, Set.of(MediaFormat.VISUALIZER), Set.of(), tokens, null, null);
        }
        if (OFFICIAL_ALONE_PATTERN.matcher(trimmed).matches()) {
            List<MetadataToken> tokens = List.of(
                    new MetadataToken(TokenCategory.OFFICIAL_CONTENT, "official", rawSpan)
            );
            return new AnnotationParseResult(true, true, Set.of(), Set.of(), tokens, null, null);
        }
        if (VIDEO_ALONE_PATTERN.matcher(trimmed).matches()) {
            List<MetadataToken> tokens = List.of(
                    new MetadataToken(TokenCategory.MEDIA_FORMAT, "video", rawSpan)
            );
            return new AnnotationParseResult(true, false, Set.of(MediaFormat.VIDEO), Set.of(), tokens, null, null);
        }
        if (AUDIO_ALONE_PATTERN.matcher(trimmed).matches()) {
            List<MetadataToken> tokens = List.of(
                    new MetadataToken(TokenCategory.MEDIA_FORMAT, "audio", rawSpan)
            );
            return new AnnotationParseResult(true, false, Set.of(MediaFormat.AUDIO), Set.of(), tokens, null, null);
        }

        // B. Remaster
        Matcher remasterMatcher = REMASTER_FULL_PATTERN.matcher(trimmed);
        if (remasterMatcher.matches()) {
            String leadYear = remasterMatcher.group(1);
            String trailYear = remasterMatcher.group(2);
            Integer year = null;
            if (leadYear != null) {
                year = Integer.parseInt(leadYear);
            } else if (trailYear != null) {
                year = Integer.parseInt(trailYear);
            }
            RemasterDetails details = new RemasterDetails(year, trimmed);
            List<MetadataToken> tokens = List.of(
                    new MetadataToken(TokenCategory.REMASTER, year != null ? year.toString() : "remaster", rawSpan)
            );
            return new AnnotationParseResult(
                    true, false, Set.of(), Set.of(VariantType.REMASTER), tokens, details, null
            );
        }

        // C. Edit & Mix
        if (RADIO_EDIT_PATTERN.matcher(trimmed).matches()) {
            List<MetadataToken> tokens = List.of(
                    new MetadataToken(TokenCategory.EDIT_OR_MIX, "radio_edit", rawSpan)
            );
            return new AnnotationParseResult(true, false, Set.of(), Set.of(VariantType.RADIO_EDIT), tokens, null, null);
        }
        if (EXTENDED_MIX_PATTERN.matcher(trimmed).matches()) {
            List<MetadataToken> tokens = List.of(
                    new MetadataToken(TokenCategory.EDIT_OR_MIX, "extended_mix", rawSpan)
            );
            return new AnnotationParseResult(true, false, Set.of(), Set.of(VariantType.EXTENDED_MIX), tokens, null, null);
        }
        if (ORIGINAL_MIX_PATTERN.matcher(trimmed).matches()) {
            List<MetadataToken> tokens = List.of(
                    new MetadataToken(TokenCategory.EDIT_OR_MIX, "original_mix", rawSpan)
            );
            return new AnnotationParseResult(true, false, Set.of(), Set.of(VariantType.ORIGINAL_MIX), tokens, null, null);
        }
        if (CLUB_MIX_PATTERN.matcher(trimmed).matches()) {
            List<MetadataToken> tokens = List.of(
                    new MetadataToken(TokenCategory.EDIT_OR_MIX, "club_mix", rawSpan)
            );
            return new AnnotationParseResult(true, false, Set.of(), Set.of(VariantType.CLUB_MIX), tokens, null, null);
        }
        if (INSTRUMENTAL_PATTERN.matcher(trimmed).matches()) {
            List<MetadataToken> tokens = List.of(
                    new MetadataToken(TokenCategory.EDIT_OR_MIX, "instrumental", rawSpan)
            );
            return new AnnotationParseResult(true, false, Set.of(), Set.of(VariantType.INSTRUMENTAL), tokens, null, null);
        }
        if (DEMO_PATTERN.matcher(trimmed).matches()) {
            List<MetadataToken> tokens = List.of(
                    new MetadataToken(TokenCategory.EDIT_OR_MIX, "demo", rawSpan)
            );
            return new AnnotationParseResult(true, false, Set.of(), Set.of(VariantType.DEMO), tokens, null, null);
        }

        // D. Performance & Recording
        Matcher liveVenueMatcher = LIVE_VENUE_PATTERN.matcher(trimmed);
        if (liveVenueMatcher.matches()) {
            String venue = liveVenueMatcher.group(1).trim();
            LivePerformanceDetails live = new LivePerformanceDetails(venue, trimmed);
            List<MetadataToken> tokens = List.of(
                    new MetadataToken(TokenCategory.PERFORMANCE, "live", rawSpan)
            );
            return new AnnotationParseResult(true, false, Set.of(), Set.of(VariantType.LIVE), tokens, null, live);
        }
        if (LIVE_PLAIN_PATTERN.matcher(trimmed).matches()) {
            LivePerformanceDetails live = new LivePerformanceDetails(null, trimmed);
            List<MetadataToken> tokens = List.of(
                    new MetadataToken(TokenCategory.PERFORMANCE, "live", rawSpan)
            );
            return new AnnotationParseResult(true, false, Set.of(), Set.of(VariantType.LIVE), tokens, null, live);
        }
        if (ACOUSTIC_PATTERN.matcher(trimmed).matches()) {
            List<MetadataToken> tokens = List.of(
                    new MetadataToken(TokenCategory.PERFORMANCE, "acoustic", rawSpan)
            );
            return new AnnotationParseResult(true, false, Set.of(), Set.of(VariantType.ACOUSTIC), tokens, null, null);
        }
        if (UNPLUGGED_PATTERN.matcher(trimmed).matches()) {
            List<MetadataToken> tokens = List.of(
                    new MetadataToken(TokenCategory.PERFORMANCE, "unplugged", rawSpan)
            );
            return new AnnotationParseResult(true, false, Set.of(), Set.of(VariantType.UNPLUGGED), tokens, null, null);
        }
        if (STUDIO_PATTERN.matcher(trimmed).matches()) {
            List<MetadataToken> tokens = List.of(
                    new MetadataToken(TokenCategory.PERFORMANCE, "studio", rawSpan)
            );
            return new AnnotationParseResult(true, false, Set.of(), Set.of(VariantType.STUDIO), tokens, null, null);
        }

        // E. Derivative / Alternate Versions
        if (SLOWED_REVERB_PATTERN.matcher(trimmed).matches()) {
            List<MetadataToken> tokens = List.of(
                    new MetadataToken(TokenCategory.DERIVATIVE_VERSION, "slowed_reverb", rawSpan)
            );
            return new AnnotationParseResult(true, false, Set.of(), Set.of(VariantType.SLOWED_REVERB), tokens, null, null);
        }
        if (SLOWED_PATTERN.matcher(trimmed).matches()) {
            List<MetadataToken> tokens = List.of(
                    new MetadataToken(TokenCategory.DERIVATIVE_VERSION, "slowed", rawSpan)
            );
            return new AnnotationParseResult(true, false, Set.of(), Set.of(VariantType.SLOWED), tokens, null, null);
        }
        if (SPED_UP_PATTERN.matcher(trimmed).matches()) {
            List<MetadataToken> tokens = List.of(
                    new MetadataToken(TokenCategory.DERIVATIVE_VERSION, "sped_up", rawSpan)
            );
            return new AnnotationParseResult(true, false, Set.of(), Set.of(VariantType.SPED_UP), tokens, null, null);
        }
        if (NIGHTCORE_PATTERN.matcher(trimmed).matches()) {
            List<MetadataToken> tokens = List.of(
                    new MetadataToken(TokenCategory.DERIVATIVE_VERSION, "nightcore", rawSpan)
            );
            return new AnnotationParseResult(true, false, Set.of(), Set.of(VariantType.NIGHTCORE), tokens, null, null);
        }
        if (REVERB_PATTERN.matcher(trimmed).matches()) {
            List<MetadataToken> tokens = List.of(
                    new MetadataToken(TokenCategory.DERIVATIVE_VERSION, "reverb", rawSpan)
            );
            return new AnnotationParseResult(true, false, Set.of(), Set.of(VariantType.REVERB), tokens, null, null);
        }
        if (KARAOKE_PATTERN.matcher(trimmed).matches()) {
            List<MetadataToken> tokens = List.of(
                    new MetadataToken(TokenCategory.DERIVATIVE_VERSION, "karaoke", rawSpan)
            );
            return new AnnotationParseResult(true, false, Set.of(), Set.of(VariantType.KARAOKE), tokens, null, null);
        }
        if (COVER_PATTERN.matcher(trimmed).matches()) {
            List<MetadataToken> tokens = List.of(
                    new MetadataToken(TokenCategory.DERIVATIVE_VERSION, "cover", rawSpan)
            );
            return new AnnotationParseResult(true, false, Set.of(), Set.of(VariantType.COVER), tokens, null, null);
        }
        if (REMIX_PATTERN.matcher(trimmed).matches()) {
            List<MetadataToken> tokens = List.of(
                    new MetadataToken(TokenCategory.DERIVATIVE_VERSION, "remix", rawSpan)
            );
            return new AnnotationParseResult(true, false, Set.of(), Set.of(VariantType.REMIX), tokens, null, null);
        }

        return new AnnotationParseResult(false, false, Set.of(), Set.of(), List.of(), null, null);
    }

    private String removeRanges(String text, List<IntRange> ranges) {
        if (ranges.isEmpty()) {
            return text;
        }
        StringBuilder sb = new StringBuilder();
        int lastIndex = 0;
        for (IntRange range : ranges) {
            if (range.start() > lastIndex) {
                sb.append(text, lastIndex, range.start());
            }
            lastIndex = Math.max(lastIndex, range.end());
        }
        if (lastIndex < text.length()) {
            sb.append(text.substring(lastIndex));
        }
        return sb.toString();
    }

    private String cleanupTitle(String title) {
        String cleaned = MULTI_SPACE_PATTERN.matcher(title).replaceAll(" ").trim();
        cleaned = DANGLING_SEPARATOR_PATTERN.matcher(cleaned).replaceAll("").trim();
        return cleaned;
    }

    private record IntRange(int start, int end) {}

    private record AnnotationParseResult(
            boolean recognized,
            boolean isOfficial,
            Set<MediaFormat> mediaFormats,
            Set<VariantType> variants,
            List<MetadataToken> tokens,
            RemasterDetails remasterDetails,
            LivePerformanceDetails liveDetails,
            List<String> featuredArtists
    ) {
        AnnotationParseResult(
                boolean recognized,
                boolean isOfficial,
                Set<MediaFormat> mediaFormats,
                Set<VariantType> variants,
                List<MetadataToken> tokens,
                RemasterDetails remasterDetails,
                LivePerformanceDetails liveDetails
        ) {
            this(recognized, isOfficial, mediaFormats, variants, tokens, remasterDetails, liveDetails, List.of());
        }
    }
}
