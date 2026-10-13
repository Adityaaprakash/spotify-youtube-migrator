package com.spotifyyoutube.migrator.matching.engine.scorer;

import com.spotifyyoutube.migrator.matching.engine.feature.CandidateTrackFeatures;
import com.spotifyyoutube.migrator.matching.engine.feature.SourceTrackFeatures;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Default implementation of {@link TitleSimilarityScorer}.
 * <p>
 * Combines exact normalized matching, character-level Levenshtein similarity,
 * token-order-insensitive alignment, and delimiter segment analysis.
 * Operates purely on Phase 5 normalized title features extracted by Phase 7B
 * without performing redundant normalization or applying version penalties.
 */
@Component
public class DefaultTitleSimilarityScorer implements TitleSimilarityScorer {

    private static final Pattern DELIMITER_SEGMENT_PATTERN = Pattern.compile("\\s*[-|:]\\s*");
    private static final Pattern WHITESPACE_PATTERN = Pattern.compile("\\s+");
    private static final Pattern PUNCTUATION_STRIP_PATTERN = Pattern.compile("[^\\p{L}\\p{N}\\s]");
    private static final Pattern BRACKET_STRIP_PATTERN = Pattern.compile(
            "\\s*[\\[\\(\\{][^\\[\\(\\{\\]\\)\\}]*[\\]\\)\\}]\\s*"
    );

    @Override
    public Double score(SourceTrackFeatures source, CandidateTrackFeatures candidate) {
        Objects.requireNonNull(source, "SourceTrackFeatures must not be null");
        Objects.requireNonNull(candidate, "CandidateTrackFeatures must not be null");

        String sourceNorm = source.normalizedTitle();
        String candidateNorm = candidate.normalizedTitle();

        // Missing metadata policy: return null (unavailable) rather than synthetic 0.0
        if (sourceNorm == null || sourceNorm.isBlank() || candidateNorm == null || candidateNorm.isBlank()) {
            return null;
        }

        String sNorm = sourceNorm.trim();
        String cNorm = candidateNorm.trim();

        // 1. Exact normalized title match
        if (sNorm.equals(cNorm)) {
            return 1.0;
        }

        // Exact clean title match (case-folded)
        String sClean = source.cleanTitle() != null ? source.cleanTitle().trim().toLowerCase(Locale.ROOT) : "";
        String cClean = candidate.cleanTitle() != null ? candidate.cleanTitle().trim().toLowerCase(Locale.ROOT) : "";
        if (!sClean.isEmpty() && sClean.equals(cClean)) {
            return 1.0;
        }

        // Bracketed annotation strip match on full candidate title (e.g. "Billie Jean (Acoustic Cover)")
        String cUnbracketed = stripBrackets(cNorm);
        if (!cUnbracketed.isEmpty() && (cUnbracketed.equals(sNorm)
                || (!sClean.isEmpty() && cUnbracketed.equals(sClean)))) {
            return sNorm.length() <= 2 ? 0.95 : 0.98;
        }

        // 2. Token extraction & Multi-set analysis
        List<String> sTokens = extractTokens(sNorm);
        List<String> cTokens = extractTokens(cNorm);

        if (sTokens.isEmpty() || cTokens.isEmpty()) {
            return 0.0;
        }

        // Word order permutation check: identical set of tokens in different order (e.g. "Lucky Get" vs "Get Lucky")
        if (areTokenListsPermutations(sTokens, cTokens)) {
            return 0.95;
        }

        // 3. Delimiter segment alignment for YouTube titles (e.g. "Daft Punk - Get Lucky")
        Double bestSegmentScore = evaluateDelimiterSegments(sNorm, sClean, cNorm, cClean);
        if (bestSegmentScore != null && bestSegmentScore >= 0.95) {
            return bestSegmentScore;
        }

        // 4. Character-level Levenshtein similarity
        double charLev = calculateLevenshteinSimilarity(sNorm, cNorm);

        // Close typo override: single-character or minor typographical divergence on full string
        if (charLev >= 0.88 && Math.abs(sNorm.length() - cNorm.length()) <= 2) {
            return Math.min(1.0, charLev);
        }

        // 5. Token Sort Similarity
        double tokenSortSim = calculateTokenSortSimilarity(sTokens, cTokens);

        // 6. Token overlap & Jaccard metrics
        Set<String> sSet = new HashSet<>(sTokens);
        Set<String> cSet = new HashSet<>(cTokens);

        Set<String> intersection = new HashSet<>(sSet);
        intersection.retainAll(cSet);

        Set<String> union = new HashSet<>(sSet);
        union.addAll(cSet);

        double jaccard = (double) intersection.size() / union.size();
        double tokenOverlap = (2.0 * intersection.size()) / (sTokens.size() + cTokens.size());

        // Zero common words safety guard: prevent unrelated titles from scoring above noise floor
        if (intersection.isEmpty()) {
            if (bestSegmentScore != null && bestSegmentScore > 0.0) {
                return Math.min(0.50, bestSegmentScore);
            }
            return Math.max(0.0, (charLev - 0.30) * 0.5);
        }

        // 7. Composite score combining character, token-sort, and token-overlap dimensions
        double combined = (0.40 * charLev) + (0.35 * tokenSortSim) + (0.25 * tokenOverlap);

        // If candidate delimiter segment showed stronger partial alignment, consider best segment
        if (bestSegmentScore != null && bestSegmentScore > combined) {
            combined = (0.60 * bestSegmentScore) + (0.40 * combined);
        }

        // Boundary clamp [0.0, 1.0]
        return Math.max(0.0, Math.min(1.0, roundToFourDecimals(combined)));
    }

    private Double evaluateDelimiterSegments(String sNorm, String sClean, String cNorm, String cClean) {
        Set<String> candidateInputs = new LinkedHashSet<>();
        if (cNorm != null && !cNorm.isBlank()) {
            candidateInputs.add(cNorm);
            String unbracketed = stripBrackets(cNorm);
            if (!unbracketed.isBlank()) {
                candidateInputs.add(unbracketed);
            }
        }
        if (cClean != null && !cClean.isBlank()) {
            candidateInputs.add(cClean);
            String unbracketed = stripBrackets(cClean);
            if (!unbracketed.isBlank()) {
                candidateInputs.add(unbracketed);
            }
        }

        List<String> allSegments = new ArrayList<>();
        for (String input : candidateInputs) {
            String[] segments = DELIMITER_SEGMENT_PATTERN.split(input);
            if (segments.length > 1) {
                for (String rawSegment : segments) {
                    String seg = rawSegment.trim();
                    if (!seg.isEmpty()) {
                        allSegments.add(seg);
                    }
                }
            }
        }

        if (allSegments.isEmpty()) {
            return null;
        }

        double maxScore = 0.0;
        for (String seg : allSegments) {
            // Exact segment match
            if (seg.equals(sNorm) || (!sClean.isEmpty() && seg.equals(sClean))) {
                // Short title guard: single/two character titles e.g. "7", "XO" receive 0.95 rather than 0.98
                return sNorm.length() <= 2 ? 0.95 : 0.98;
            }

            // Segment with bracketed annotations stripped e.g. "billie jean (acoustic cover)" -> "billie jean"
            String unbracketedSeg = stripBrackets(seg);
            if (!unbracketedSeg.isEmpty() && (unbracketedSeg.equals(sNorm)
                    || (!sClean.isEmpty() && unbracketedSeg.equals(sClean)))) {
                return sNorm.length() <= 2 ? 0.95 : 0.98;
            }

            // Clean segment comparison
            String cleanSeg = PUNCTUATION_STRIP_PATTERN.matcher(seg).replaceAll("").trim();
            String cleanSource = PUNCTUATION_STRIP_PATTERN.matcher(sNorm).replaceAll("").trim();
            String cleanSourceClean = !sClean.isEmpty()
                    ? PUNCTUATION_STRIP_PATTERN.matcher(sClean).replaceAll("").trim()
                    : "";
            if (!cleanSeg.isEmpty() && (cleanSeg.equals(cleanSource)
                    || (!cleanSourceClean.isEmpty() && cleanSeg.equals(cleanSourceClean)))) {
                return sNorm.length() <= 2 ? 0.92 : 0.96;
            }

            String cleanUnbracketed = PUNCTUATION_STRIP_PATTERN.matcher(unbracketedSeg).replaceAll("").trim();
            if (!cleanUnbracketed.isEmpty() && (cleanUnbracketed.equals(cleanSource)
                    || (!cleanSourceClean.isEmpty() && cleanUnbracketed.equals(cleanSourceClean)))) {
                return sNorm.length() <= 2 ? 0.92 : 0.96;
            }

            double lev = Math.max(
                    calculateLevenshteinSimilarity(sNorm, seg),
                    !unbracketedSeg.isEmpty() ? calculateLevenshteinSimilarity(sNorm, unbracketedSeg) : 0.0
            );
            double sort = Math.max(
                    calculateTokenSortSimilarity(extractTokens(sNorm), extractTokens(seg)),
                    !unbracketedSeg.isEmpty()
                            ? calculateTokenSortSimilarity(extractTokens(sNorm), extractTokens(unbracketedSeg))
                            : 0.0
            );
            double best = Math.max(lev, sort);
            if (best > maxScore) {
                maxScore = best;
            }
        }

        return maxScore > 0.0 ? maxScore : null;
    }

    private String stripBrackets(String text) {
        if (text == null || text.isBlank()) {
            return "";
        }
        String stripped = BRACKET_STRIP_PATTERN.matcher(text).replaceAll(" ");
        return WHITESPACE_PATTERN.matcher(stripped).replaceAll(" ").trim();
    }

    private List<String> extractTokens(String text) {
        if (text == null || text.isBlank()) {
            return Collections.emptyList();
        }
        String stripped = PUNCTUATION_STRIP_PATTERN.matcher(text).replaceAll(" ");
        String[] parts = WHITESPACE_PATTERN.split(stripped.trim());
        List<String> tokens = new ArrayList<>(parts.length);
        for (String part : parts) {
            String trimmed = part.trim();
            if (!trimmed.isEmpty()) {
                tokens.add(trimmed);
            }
        }
        return tokens;
    }

    private boolean areTokenListsPermutations(List<String> a, List<String> b) {
        if (a.size() != b.size()) {
            return false;
        }
        List<String> sortedA = new ArrayList<>(a);
        List<String> sortedB = new ArrayList<>(b);
        Collections.sort(sortedA);
        Collections.sort(sortedB);
        return sortedA.equals(sortedB);
    }

    private double calculateTokenSortSimilarity(List<String> aTokens, List<String> bTokens) {
        if (aTokens.isEmpty() || bTokens.isEmpty()) {
            return 0.0;
        }
        List<String> sortedA = new ArrayList<>(aTokens);
        List<String> sortedB = new ArrayList<>(bTokens);
        Collections.sort(sortedA);
        Collections.sort(sortedB);

        String strA = String.join(" ", sortedA);
        String strB = String.join(" ", sortedB);
        return calculateLevenshteinSimilarity(strA, strB);
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

        int distance = computeLevenshteinDistance(s1, s2);
        int maxLen = Math.max(len1, len2);
        return 1.0 - ((double) distance / maxLen);
    }

    private int computeLevenshteinDistance(String s1, String s2) {
        int len1 = s1.length();
        int len2 = s2.length();

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

        return previousRow[len2];
    }

    private double roundToFourDecimals(double value) {
        return Math.round(value * 10000.0) / 10000.0;
    }
}
