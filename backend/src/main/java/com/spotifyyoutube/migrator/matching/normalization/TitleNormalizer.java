package com.spotifyyoutube.migrator.matching.normalization;

import org.springframework.stereotype.Component;

import java.text.Normalizer;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Normalizes track titles for deterministic comparison between Spotify and YouTube.
 * Applies Unicode NFC normalization, case folding (Locale.ROOT), punctuation standardization,
 * and conservative promotional annotation removal (e.g. Official Audio/Video) while
 * strictly preserving meaningful version distinctions (e.g. Live, Acoustic, Remix).
 */
@Component
public class TitleNormalizer {

    private static final Pattern MULTI_SPACE_PATTERN = Pattern.compile("\\s+");

    // Promotional annotations in brackets/parentheses e.g. (Official Audio), [Official Video]
    private static final Pattern PROMOTIONAL_BRACKET_PATTERN = Pattern.compile(
            "(?i)(?:\\s*[-|])?\\s*[\\[\\(]\\s*official\\s+(?:music\\s+|lyric[s]?\\s+)?(?:video|audio|visualizer)\\s*[\\]\\)]"
    );

    // Trailing promotional suffixes separated by dash or pipe e.g. " - Official Video", " | Official Audio"
    private static final Pattern PROMOTIONAL_TRAILING_SEPARATOR_PATTERN = Pattern.compile(
            "(?i)\\s*[-|]\\s*official\\s+(?:music\\s+|lyric[s]?\\s+)?(?:video|audio|visualizer)\\s*$"
    );

    // Dangling trailing separators left after promotional suffix removal
    private static final Pattern DANGLING_TRAILING_SEPARATOR_PATTERN = Pattern.compile(
            "\\s*[-|]\\s*$"
    );

    // Smart/curly quotes and apostrophe variants to standard ASCII
    private static final Pattern APOSTROPHE_VARIANTS = Pattern.compile("[\u2019\u2018\u02BC\u0060\u00B4]");
    private static final Pattern DOUBLE_QUOTE_VARIANTS = Pattern.compile("[\u201C\u201D\u201E\u00AB\u00BB\uFF02]");

    // Dash variants to standard hyphen-minus '-'
    private static final Pattern DASH_VARIANTS = Pattern.compile("[\u2010\u2011\u2012\u2013\u2014\u2015\u2212\uFE63\uFF0D]");

    /**
     * Normalizes a raw track title for matching comparison.
     *
     * @param title the raw track title, may be null or blank
     * @return normalized title string, or empty string if input is null or blank
     */
    public String normalize(String title) {
        if (title == null || title.isBlank()) {
            return "";
        }

        // 1. Unicode canonical composition (NFC)
        String normalized = Normalizer.normalize(title, Normalizer.Form.NFC);

        // 2. Standardize quotation marks and apostrophes
        normalized = APOSTROPHE_VARIANTS.matcher(normalized).replaceAll("'");
        normalized = DOUBLE_QUOTE_VARIANTS.matcher(normalized).replaceAll("\"");

        // 3. Standardize dash variants
        normalized = DASH_VARIANTS.matcher(normalized).replaceAll("-");

        // 4. Conservative promotional annotation removal (only if substantive title remains)
        String stripped = PROMOTIONAL_BRACKET_PATTERN.matcher(normalized).replaceAll("");
        stripped = PROMOTIONAL_TRAILING_SEPARATOR_PATTERN.matcher(stripped).replaceAll("");
        stripped = DANGLING_TRAILING_SEPARATOR_PATTERN.matcher(stripped).replaceAll("");
        if (!stripped.isBlank()) {
            normalized = stripped;
        }

        // 5. Clean up extra spaces and collapse repeated whitespace
        normalized = MULTI_SPACE_PATTERN.matcher(normalized).replaceAll(" ").trim();

        // 6. Case folding using Locale.ROOT
        return normalized.toLowerCase(Locale.ROOT);
    }
}
