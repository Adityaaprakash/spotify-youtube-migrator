package com.spotifyyoutube.migrator.matching.normalization;

import org.springframework.stereotype.Component;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Normalizes artist metadata for matching comparisons without destroying artist identity.
 * Supports Unicode NFC composition, case folding (Locale.ROOT), whitespace collapsing,
 * and structured parsing of featured-artist credits (e.g. feat., ft., featuring)
 * while preserving bands and collaborations containing ampersands, commas, or plus signs.
 */
@Component
public class ArtistNormalizer {

    private static final Pattern MULTI_SPACE_PATTERN = Pattern.compile("\\s+");

    // Smart/curly quotes and apostrophe variants to standard ASCII
    private static final Pattern APOSTROPHE_VARIANTS = Pattern.compile("[\u2019\u2018\u02BC\u0060\u00B4]");
    private static final Pattern DOUBLE_QUOTE_VARIANTS = Pattern.compile("[\u201C\u201D\u201E\u00AB\u00BB\uFF02]");

    // Dash variants to standard hyphen-minus '-'
    private static final Pattern DASH_VARIANTS = Pattern.compile("[\u2010\u2011\u2012\u2013\u2014\u2015\u2212\uFE63\uFF0D]");

    // Parenthetical or bracketed featured credit: e.g. " (feat. Guest)", " [ft. Guest]"
    private static final Pattern PARENTHETICAL_FEATURED_PATTERN = Pattern.compile(
            "(?i)\\s*[\\[\\(](?:feat\\.?|ft\\.?|featuring)\\s+([^\\]\\)]+)[\\]\\)]"
    );

    // Unbracketed trailing featured credit: e.g. " feat. Guest", " ft Guest", " featuring Guest"
    private static final Pattern TRAILING_FEATURED_PATTERN = Pattern.compile(
            "(?i)\\s+(?:feat\\.?|ft\\.?|featuring)\\s+(.+)$"
    );

    // Separators between multiple featured artists: e.g. "Guest 1, Guest 2 & Guest 3"
    private static final Pattern FEATURED_DELIMITER_PATTERN = Pattern.compile(
            "(?i)\\s*(?:,\\s*|\\s+&\\s+|\\s+and\\s+)"
    );

    // Dangling trailing separators preceding featured credits: e.g. "Artist - feat. Guest"
    private static final Pattern TRAILING_SEPARATOR_PATTERN = Pattern.compile(
            "\\s*[-|]\\s*$"
    );

    /**
     * Normalizes a single artist name.
     *
     * @param artistName raw artist name, may be null or blank
     * @return normalized artist string, or empty string if input is null or blank
     */
    public String normalize(String artistName) {
        if (artistName == null || artistName.isBlank()) {
            return "";
        }

        // 1. Unicode canonical composition (NFC)
        String normalized = Normalizer.normalize(artistName, Normalizer.Form.NFC);

        // 2. Standardize quotation marks and apostrophes
        normalized = APOSTROPHE_VARIANTS.matcher(normalized).replaceAll("'");
        normalized = DOUBLE_QUOTE_VARIANTS.matcher(normalized).replaceAll("\"");

        // 3. Standardize dash variants
        normalized = DASH_VARIANTS.matcher(normalized).replaceAll("-");

        // 4. Collapse whitespace and trim
        normalized = MULTI_SPACE_PATTERN.matcher(normalized).replaceAll(" ").trim();

        // 5. Case folding using Locale.ROOT
        return normalized.toLowerCase(Locale.ROOT);
    }

    /**
     * Parses an artist credit string into primary and featured artist components.
     * Recognizes token-bounded 'feat.', 'ft.', 'featuring' markers without naive
     * comma or ampersand splitting that would destroy band identities.
     *
     * @param artistCredit raw artist credit string, may be null or blank
     * @return structured NormalizedArtistCredit
     */
    public NormalizedArtistCredit parseArtistCredit(String artistCredit) {
        if (artistCredit == null || artistCredit.isBlank()) {
            return new NormalizedArtistCredit("", List.of());
        }

        // Check parenthetical featured first: e.g. "Dua Lipa (feat. DaBaby)"
        Matcher parenMatcher = PARENTHETICAL_FEATURED_PATTERN.matcher(artistCredit);
        if (parenMatcher.find()) {
            String rawPrimary = artistCredit.substring(0, parenMatcher.start());
            rawPrimary = TRAILING_SEPARATOR_PATTERN.matcher(rawPrimary).replaceAll("");
            String rawFeatured = parenMatcher.group(1);
            String primary = normalize(rawPrimary);
            List<String> featured = parseFeaturedArtistTokens(rawFeatured);
            return new NormalizedArtistCredit(primary, featured);
        }

        // Check unbracketed trailing featured: e.g. "Dua Lipa feat. DaBaby"
        Matcher trailingMatcher = TRAILING_FEATURED_PATTERN.matcher(artistCredit);
        if (trailingMatcher.find()) {
            String rawPrimary = artistCredit.substring(0, trailingMatcher.start());
            rawPrimary = TRAILING_SEPARATOR_PATTERN.matcher(rawPrimary).replaceAll("");
            String rawFeatured = trailingMatcher.group(1);
            String primary = normalize(rawPrimary);
            List<String> featured = parseFeaturedArtistTokens(rawFeatured);
            return new NormalizedArtistCredit(primary, featured);
        }

        // No featured marker found: whole string is treated as primary artist
        return new NormalizedArtistCredit(normalize(artistCredit), List.of());
    }

    /**
     * Normalizes a list of discrete artist names, preserving order and removing blanks.
     *
     * @param artists list of raw artist names, may be null or empty
     * @return unmodifiable list of normalized artist names in original order
     */
    public List<String> normalizeArtistList(List<String> artists) {
        if (artists == null || artists.isEmpty()) {
            return List.of();
        }

        List<String> normalizedList = new ArrayList<>();
        for (String artist : artists) {
            String normalized = normalize(artist);
            if (!normalized.isEmpty()) {
                normalizedList.add(normalized);
            }
        }
        return List.copyOf(normalizedList);
    }

    private List<String> parseFeaturedArtistTokens(String rawFeatured) {
        if (rawFeatured == null || rawFeatured.isBlank()) {
            return List.of();
        }

        String[] tokens = FEATURED_DELIMITER_PATTERN.split(rawFeatured);
        List<String> result = new ArrayList<>();
        for (String token : tokens) {
            String normalizedToken = normalize(token);
            if (!normalizedToken.isEmpty()) {
                result.add(normalizedToken);
            }
        }
        return List.copyOf(result);
    }
}
