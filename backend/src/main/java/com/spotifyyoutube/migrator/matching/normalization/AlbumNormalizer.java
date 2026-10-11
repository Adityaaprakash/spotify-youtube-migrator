package com.spotifyyoutube.migrator.matching.normalization;

import org.springframework.stereotype.Component;

import java.text.Normalizer;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Normalizes album metadata for deterministic comparison between Spotify and destination metadata.
 * Applies Unicode NFC normalization, case folding (Locale.ROOT), whitespace collapsing,
 * and punctuation standardization (apostrophes, quotes, dashes) while strictly preserving
 * substantive album editions, live venues, remasters, and release qualifiers.
 */
@Component
public class AlbumNormalizer {

    private static final Pattern MULTI_SPACE_PATTERN = Pattern.compile("\\s+");

    // Smart/curly quotes and apostrophe variants to standard ASCII
    private static final Pattern APOSTROPHE_VARIANTS = Pattern.compile("[\u2019\u2018\u02BC\u0060\u00B4]");
    private static final Pattern DOUBLE_QUOTE_VARIANTS = Pattern.compile("[\u201C\u201D\u201E\u00AB\u00BB\uFF02]");

    // Dash variants to standard hyphen-minus '-'
    private static final Pattern DASH_VARIANTS = Pattern.compile("[\u2010\u2011\u2012\u2013\u2014\u2015\u2212\uFE63\uFF0D]");

    /**
     * Normalizes a raw album name for matching comparison.
     *
     * @param albumName the raw album name, may be null or blank
     * @return normalized album string, or empty string if input is null or blank
     */
    public String normalize(String albumName) {
        if (albumName == null || albumName.isBlank()) {
            return "";
        }

        // 1. Unicode canonical composition (NFC)
        String normalized = Normalizer.normalize(albumName, Normalizer.Form.NFC);

        // 2. Standardize quotation marks and apostrophes
        normalized = APOSTROPHE_VARIANTS.matcher(normalized).replaceAll("'");
        normalized = DOUBLE_QUOTE_VARIANTS.matcher(normalized).replaceAll("\"");

        // 3. Standardize dash variants
        normalized = DASH_VARIANTS.matcher(normalized).replaceAll("-");

        // 4. Clean up whitespace: replace repeated spaces, tabs, newlines with single space and trim
        normalized = MULTI_SPACE_PATTERN.matcher(normalized).replaceAll(" ").trim();

        // 5. Case folding using Locale.ROOT
        return normalized.toLowerCase(Locale.ROOT);
    }
}
