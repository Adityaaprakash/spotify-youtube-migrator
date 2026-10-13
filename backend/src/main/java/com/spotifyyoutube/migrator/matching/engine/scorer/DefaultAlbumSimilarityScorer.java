package com.spotifyyoutube.migrator.matching.engine.scorer;

import com.spotifyyoutube.migrator.matching.engine.feature.CandidateTrackFeatures;
import com.spotifyyoutube.migrator.matching.engine.feature.SourceTrackFeatures;
import com.spotifyyoutube.migrator.matching.normalization.AlbumNormalizer;
import org.springframework.stereotype.Component;

import java.text.Normalizer;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Default implementation of {@link AlbumSimilarityScorer}.
 * <p>
 * Reuses Phase 5 {@link AlbumNormalizer} to evaluate album metadata alignment between Spotify and
 * destination candidates. Distinguishes missing metadata from confirmed mismatches by returning
 * {@code null} when album evidence is absent or unverifiable on either side.
 * <p>
 * Provides robust protection against substring collisions (e.g. {@code "War"} vs {@code "Star Wars"})
 * and generic title collisions (e.g. {@code "Greatest Hits"} across different artists).
 */
@Component
public class DefaultAlbumSimilarityScorer implements AlbumSimilarityScorer {

    private static final Pattern DIACRITICS_PATTERN = Pattern.compile("\\p{M}+");

    // Matches explicit "Album: <title>" lines in YouTube video descriptions
    private static final Pattern ALBUM_LINE_PATTERN = Pattern.compile(
            "(?i)(?:^|[\\r\\n])\\s*album\\s*[:·-]\\s*([^\\r\\n]+)"
    );

    private final AlbumNormalizer albumNormalizer;

    public DefaultAlbumSimilarityScorer(AlbumNormalizer albumNormalizer) {
        this.albumNormalizer = Objects.requireNonNull(albumNormalizer, "AlbumNormalizer must not be null");
    }

    public DefaultAlbumSimilarityScorer() {
        this(new AlbumNormalizer());
    }

    @Override
    public Double score(SourceTrackFeatures source, CandidateTrackFeatures candidate) {
        Objects.requireNonNull(source, "SourceTrackFeatures must not be null");
        Objects.requireNonNull(candidate, "CandidateTrackFeatures must not be null");

        String sourceAlbum = source.normalizedAlbum();
        if (sourceAlbum == null || sourceAlbum.isBlank()) {
            return null;
        }

        String candidateAlbum = extractCandidateAlbum(candidate);
        if (candidateAlbum == null || candidateAlbum.isBlank()) {
            return null;
        }

        return score(sourceAlbum, candidateAlbum);
    }

    @Override
    public Double score(String sourceAlbum, String candidateAlbum) {
        if (sourceAlbum == null || sourceAlbum.isBlank() || candidateAlbum == null || candidateAlbum.isBlank()) {
            return null;
        }

        String sNorm = albumNormalizer.normalize(sourceAlbum);
        String cNorm = albumNormalizer.normalize(candidateAlbum);

        if (sNorm.isBlank() || cNorm.isBlank()) {
            return null;
        }

        // 1. Exact normalized match
        if (sNorm.equals(cNorm)) {
            return 1.0;
        }

        // 2. Whitespace-collapsed equality
        if (sNorm.replace(" ", "").equals(cNorm.replace(" ", ""))) {
            return 1.0;
        }

        // 3. Canonically equivalent accent / diacritic folding (e.g. "Ágætis byrjun" vs "Agaetis byrjun")
        String sNoAccents = stripDiacritics(sNorm);
        String cNoAccents = stripDiacritics(cNorm);
        if (!sNoAccents.isEmpty() && sNoAccents.equals(cNoAccents)) {
            return 1.0;
        }

        // 4. Minor typographical differences on long album titles (Levenshtein >= 0.88, len diff <= 2)
        double lev = calculateLevenshteinSimilarity(sNoAccents, cNoAccents);
        if (lev >= 0.88 && Math.abs(sNoAccents.length() - cNoAccents.length()) <= 2) {
            return roundToFourDecimals(lev);
        }

        // 5. Distinct albums, generic collisions, or substring collisions
        return 0.0;
    }

    private String extractCandidateAlbum(CandidateTrackFeatures candidate) {
        String description = candidate.description();
        if (description == null || description.isBlank()) {
            return null;
        }

        // Try explicit "Album: <name>" label pattern first
        Matcher matcher = ALBUM_LINE_PATTERN.matcher(description);
        if (matcher.find()) {
            return matcher.group(1).trim();
        }

        // Try standard YouTube topic delivery layout:
        // Provided to YouTube by <Distributor>
        // <Title> · <Artist>
        // <Album Title>
        // ℗ <Year> ...
        if (description.contains("Provided to YouTube by")) {
            String[] lines = description.split("\\r?\\n");
            int nonBlankCount = 0;
            for (String line : lines) {
                String trimmed = line.trim();
                if (trimmed.isEmpty()) {
                    continue;
                }
                nonBlankCount++;
                // Third non-blank line in standard delivery layout is the album name
                if (nonBlankCount == 3) {
                    if (!trimmed.startsWith("℗")
                            && !trimmed.startsWith("©")
                            && !trimmed.toLowerCase().startsWith("auto-generated")
                            && !trimmed.toLowerCase().startsWith("released on")) {
                        return trimmed;
                    }
                    break;
                }
            }
        }

        return null;
    }

    private static String stripDiacritics(String str) {
        if (str == null) {
            return "";
        }
        String nfd = Normalizer.normalize(str, Normalizer.Form.NFD);
        String stripped = DIACRITICS_PATTERN.matcher(nfd).replaceAll("");
        return foldSpecialCharacters(stripped);
    }

    private static String foldSpecialCharacters(String str) {
        if (str == null || str.isEmpty()) {
            return "";
        }
        return str.replace("æ", "ae")
                .replace("Æ", "ae")
                .replace("œ", "oe")
                .replace("Œ", "oe")
                .replace("ø", "o")
                .replace("Ø", "o")
                .replace("ð", "d")
                .replace("Ð", "d")
                .replace("þ", "th")
                .replace("Þ", "th")
                .replace("ß", "ss");
    }

    private double calculateLevenshteinSimilarity(String s1, String s2) {
        if (s1 == null || s2 == null) {
            return 0.0;
        }
        if (s1.equals(s2)) {
            return 1.0;
        }
        int len1 = s1.length();
        int len2 = s2.length();
        if (len1 == 0 || len2 == 0) {
            return 0.0;
        }

        int[] previousRow = new int[len2 + 1];
        int[] currentRow = new int[len2 + 1];

        for (int j = 0; j <= len2; j++) {
            previousRow[j] = j;
        }

        for (int i = 1; i <= len1; i++) {
            currentRow[0] = i;
            char char1 = s1.charAt(i - 1);
            for (int j = 1; j <= len2; j++) {
                char char2 = s2.charAt(j - 1);
                int cost = (char1 == char2) ? 0 : 1;
                currentRow[j] = Math.min(
                        Math.min(currentRow[j - 1] + 1, previousRow[j] + 1),
                        previousRow[j - 1] + cost
                );
            }
            System.arraycopy(currentRow, 0, previousRow, 0, len2 + 1);
        }

        int distance = previousRow[len2];
        int maxLen = Math.max(len1, len2);
        return 1.0 - ((double) distance / maxLen);
    }

    private double roundToFourDecimals(double value) {
        return Math.round(value * 10000.0) / 10000.0;
    }
}
