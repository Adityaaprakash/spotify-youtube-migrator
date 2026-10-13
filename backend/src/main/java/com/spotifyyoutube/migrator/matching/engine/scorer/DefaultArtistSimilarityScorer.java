package com.spotifyyoutube.migrator.matching.engine.scorer;

import com.spotifyyoutube.migrator.matching.engine.feature.CandidateTrackFeatures;
import com.spotifyyoutube.migrator.matching.engine.feature.SourceTrackFeatures;
import com.spotifyyoutube.migrator.matching.normalization.ArtistNormalizer;
import com.spotifyyoutube.migrator.matching.normalization.NormalizedArtistCredit;
import org.springframework.stereotype.Component;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Default implementation of {@link ArtistSimilarityScorer}.
 * <p>
 * Evaluates artist similarity using Phase 5 {@link ArtistNormalizer} and Phase 7B feature contracts.
 * Prioritizes primary artist agreement over featured artist overlap, prevents shared featured artists
 * from falsely masquerading as primary matches, and uses optimal 1-to-1 bipartite matching across
 * multi-artist collaborations to prevent duplicate-credit score inflation.
 */
@Component
public class DefaultArtistSimilarityScorer implements ArtistSimilarityScorer {

    private static final Pattern DIACRITICS_PATTERN = Pattern.compile("\\p{M}+");

    private final ArtistNormalizer artistNormalizer;

    public DefaultArtistSimilarityScorer(ArtistNormalizer artistNormalizer) {
        this.artistNormalizer = Objects.requireNonNull(artistNormalizer, "ArtistNormalizer must not be null");
    }

    public DefaultArtistSimilarityScorer() {
        this(new ArtistNormalizer());
    }

    @Override
    public Double score(SourceTrackFeatures source, CandidateTrackFeatures candidate) {
        Objects.requireNonNull(source, "SourceTrackFeatures must not be null");
        Objects.requireNonNull(candidate, "CandidateTrackFeatures must not be null");

        String sPrimary = source.primaryArtist();
        String cNormChannel = candidate.normalizedChannelOrArtist();
        String cRawChannel = candidate.channelOrArtist() != null
                ? candidate.channelOrArtist().trim()
                : (cNormChannel != null ? cNormChannel : "");

        // 1. Missing data policy: return null (unavailable) rather than false mismatch
        if (sPrimary == null || sPrimary.isBlank() || cNormChannel == null || cNormChannel.isBlank()) {
            return null;
        }

        String sourcePrimary = sPrimary.trim().toLowerCase(Locale.ROOT);
        String candidateChannel = cNormChannel.trim().toLowerCase(Locale.ROOT);

        // Extract discrete source artists and featured credits
        List<String> sourceDiscrete = extractDiscreteSourceArtists(source, sourcePrimary);
        List<String> sourceFeatured = source.featuredArtists() != null ? source.featuredArtists() : List.of();

        // 2. Verified official artist channel equivalence from Phase 7B (VEVO, Topic, or verified handle)
        if (candidate.officialSignal().isArtistVerifiedName() && sourceDiscrete.size() <= 1) {
            return 1.0;
        }

        // 3. Parse candidate channel / credit using ArtistNormalizer
        NormalizedArtistCredit parsedCandidate = artistNormalizer.parseArtistCredit(cRawChannel);
        String candParsedPrimary = parsedCandidate.primaryArtist().toLowerCase(Locale.ROOT);
        List<String> candParsedFeatured = parsedCandidate.featuredArtists();

        List<String> candDiscrete = assembleCandidateArtistList(candParsedPrimary, candParsedFeatured, candidateChannel);

        // 4. Evaluate single-artist exact match fast-path
        if (sourceDiscrete.size() <= 1 && sourceFeatured.isEmpty() && candDiscrete.size() <= 1) {
            double singleSim = compareArtistNames(sourcePrimary, candDiscrete.get(0));
            if (singleSim >= 0.85) {
                return singleSim;
            }
            if (candidate.officialSignal().isArtistVerifiedName()) {
                return 1.0;
            }
            return 0.0;
        }

        // 5. Evaluate primary and featured artist alignment when primary matches
        double primaryToPrimarySim = compareArtistNames(sourcePrimary, candParsedPrimary);
        if (primaryToPrimarySim >= 0.90 && sourceDiscrete.size() <= 1) {
            // Source primary matches candidate primary
            if (sourceFeatured.isEmpty() && candParsedFeatured.isEmpty()) {
                return 1.0;
            }
            if (sourceFeatured.isEmpty()) {
                // Candidate lists an additional featured credit (e.g. remix or guest), primary is exact
                return 0.98;
            }
            if (candParsedFeatured.isEmpty()) {
                // Candidate channel omitted featured artist credit (e.g. official primary artist channel upload)
                return primaryToPrimarySim >= 0.98 ? 1.0 : roundToFourDecimals(primaryToPrimarySim);
            }
            // Both source and candidate list featured credits: calculate featured overlap
            double featuredOverlap = calculateFeaturedOverlap(sourceFeatured, candParsedFeatured);
            if (featuredOverlap >= 0.99) {
                return 1.0;
            }
            return Math.min(1.0, roundToFourDecimals(0.85 + (0.15 * featuredOverlap)));
        }

        // 6. Check if candidate channel is ONLY a source featured artist (primary differs)
        if (isArtistContainedInList(candidateChannel, sourceFeatured)) {
            // Candidate channel is ONLY the featured artist (e.g. guest upload), but primary differs!
            // Policy: never allow featured-only match to yield an inflated score
            return 0.35;
        }

        // 7. Check if primary artists differ, but share a featured artist
        if (!sourceFeatured.isEmpty() && !candParsedFeatured.isEmpty() && primaryToPrimarySim < 0.50) {
            double featuredOverlap = calculateFeaturedOverlap(sourceFeatured, candParsedFeatured);
            if (featuredOverlap > 0.0) {
                // Strictly bounded low score for featured-only overlap when primary artists differ
                return Math.min(0.40, roundToFourDecimals(0.25 + (0.15 * featuredOverlap)));
            }
        }

        // 8. Multi-artist optimal 1-to-1 assignment
        return evaluateMultiArtistAssignment(sourceDiscrete, candDiscrete, sourcePrimary, candParsedPrimary);
    }

    private List<String> extractDiscreteSourceArtists(SourceTrackFeatures source, String sourcePrimary) {
        List<String> list = new ArrayList<>();
        if (source.normalizedArtists() != null && !source.normalizedArtists().isEmpty()) {
            for (String a : source.normalizedArtists()) {
                if (a != null && !a.isBlank()) {
                    String norm = a.trim().toLowerCase(Locale.ROOT);
                    if (!list.contains(norm)) {
                        list.add(norm);
                    }
                }
            }
        }
        if (list.isEmpty() && sourcePrimary != null && !sourcePrimary.isBlank()) {
            list.add(sourcePrimary);
        }
        return list;
    }

    private List<String> assembleCandidateArtistList(String primary, List<String> featured, String channel) {
        Set<String> unique = new LinkedHashSet<>();
        if (primary != null && !primary.isBlank()) {
            unique.add(primary.toLowerCase(Locale.ROOT));
        }
        if (featured != null) {
            for (String f : featured) {
                if (f != null && !f.isBlank()) {
                    unique.add(f.toLowerCase(Locale.ROOT));
                }
            }
        }
        if (unique.isEmpty() && channel != null && !channel.isBlank()) {
            unique.add(channel.toLowerCase(Locale.ROOT));
        }
        return new ArrayList<>(unique);
    }

    private Double evaluateMultiArtistAssignment(List<String> sourceArtists, List<String> candArtists,
                                                 String sourcePrimary, String candPrimary) {
        int n = sourceArtists.size();
        int m = candArtists.size();
        if (n == 0 || m == 0) {
            return 0.0;
        }

        // Build pairwise similarity matrix using compareArtistNames
        double[][] matrix = new double[n][m];
        for (int i = 0; i < n; i++) {
            for (int j = 0; j < m; j++) {
                matrix[i][j] = compareArtistNames(sourceArtists.get(i), candArtists.get(j));
            }
        }

        // Find optimal 1-to-1 bipartite assignment
        AssignmentResult result = findOptimalAssignment(matrix, n, m);
        if (result.totalWeight <= 0.0) {
            return 0.0;
        }

        int matchCount = 0;
        boolean sourcePrimaryMatched = false;
        boolean sourcePrimaryMatchedAsCandPrimary = false;

        for (int i = 0; i < n; i++) {
            int matchedCol = result.rowToCol[i];
            if (matchedCol != -1 && matrix[i][matchedCol] >= 0.85) {
                matchCount++;
                String sArtist = sourceArtists.get(i);
                if (sArtist.equals(sourcePrimary)) {
                    sourcePrimaryMatched = true;
                    if (matchedCol == 0 && candArtists.get(0).equals(candPrimary)) {
                        sourcePrimaryMatchedAsCandPrimary = true;
                    }
                }
            }
        }

        // If all artists in both sets matched
        if (matchCount == n && matchCount == m) {
            if (sourcePrimaryMatchedAsCandPrimary) {
                return roundToFourDecimals(result.totalWeight / n);
            }
            // Inverted order (e.g. Calvin Harris & Dua Lipa vs Dua Lipa feat. Calvin Harris)
            return 0.90;
        }

        // If source primary was not matched at all and only secondary/featured artists matched
        if (!sourcePrimaryMatched) {
            return Math.min(0.40, roundToFourDecimals((result.totalWeight / Math.max(n, m)) * 0.5));
        }

        // Multi-artist score normalized by max artists to prevent duplicate credit inflation
        int maxArtists = Math.max(n, m);
        return roundToFourDecimals(result.totalWeight / maxArtists);
    }

    private static class AssignmentResult {
        final double totalWeight;
        final int[] rowToCol;

        AssignmentResult(double totalWeight, int[] rowToCol) {
            this.totalWeight = totalWeight;
            this.rowToCol = rowToCol;
        }
    }

    private AssignmentResult findOptimalAssignment(double[][] matrix, int n, int m) {
        boolean[] assignedCols = new boolean[m];
        int[] currentAssignment = new int[n];
        for (int i = 0; i < n; i++) {
            currentAssignment[i] = -1;
        }
        int[] bestAssignment = new int[n];
        for (int i = 0; i < n; i++) {
            bestAssignment[i] = -1;
        }
        double[] maxWeight = new double[] { 0.0 };

        searchAssignment(matrix, 0, assignedCols, currentAssignment, 0.0, n, m, bestAssignment, maxWeight);
        return new AssignmentResult(maxWeight[0], bestAssignment);
    }

    private void searchAssignment(double[][] matrix, int row, boolean[] assignedCols,
                                  int[] currentAssignment, double currentWeight,
                                  int n, int m, int[] bestAssignment, double[] maxWeight) {
        if (row >= n) {
            if (currentWeight > maxWeight[0]) {
                maxWeight[0] = currentWeight;
                System.arraycopy(currentAssignment, 0, bestAssignment, 0, n);
            }
            return;
        }

        // Try matching this row to an unassigned column
        for (int col = 0; col < m; col++) {
            if (!assignedCols[col] && matrix[row][col] > 0.0) {
                assignedCols[col] = true;
                currentAssignment[row] = col;
                searchAssignment(matrix, row + 1, assignedCols, currentAssignment,
                        currentWeight + matrix[row][col], n, m, bestAssignment, maxWeight);
                currentAssignment[row] = -1;
                assignedCols[col] = false;
            }
        }

        // Also consider skipping matching this row
        searchAssignment(matrix, row + 1, assignedCols, currentAssignment,
                currentWeight, n, m, bestAssignment, maxWeight);
    }

    private double compareArtistNames(String name1, String name2) {
        if (name1 == null || name1.isBlank() || name2 == null || name2.isBlank()) {
            return 0.0;
        }
        String n1 = name1.trim().toLowerCase(Locale.ROOT);
        String n2 = name2.trim().toLowerCase(Locale.ROOT);

        // Exact match
        if (n1.equals(n2)) {
            return 1.0;
        }

        // Space-collapsed match (e.g. "Daft Punk" vs "DaftPunk")
        if (n1.replace(" ", "").equals(n2.replace(" ", ""))) {
            return 1.0;
        }

        // Accent / diacritic folded match (e.g. "Beyoncé" vs "Beyonce", "Sigur Rós" vs "Sigur Ros")
        String n1NoAccents = stripDiacritics(n1);
        String n2NoAccents = stripDiacritics(n2);
        if (!n1NoAccents.isEmpty() && n1NoAccents.equals(n2NoAccents)) {
            return 1.0;
        }

        // High Levenshtein edit distance similarity with strict length bounds
        double lev = calculateLevenshteinSimilarity(n1NoAccents, n2NoAccents);
        if (lev >= 0.85 && Math.abs(n1NoAccents.length() - n2NoAccents.length()) <= 2) {
            return roundToFourDecimals(lev);
        }

        // Completely distinct or substring collision (e.g. "Future" vs "Afterlife")
        return 0.0;
    }

    private static String stripDiacritics(String str) {
        if (str == null) {
            return "";
        }
        String nfd = Normalizer.normalize(str, Normalizer.Form.NFD);
        return DIACRITICS_PATTERN.matcher(nfd).replaceAll("");
    }

    private boolean isArtistContainedInList(String target, List<String> artists) {
        if (target == null || target.isBlank() || artists == null || artists.isEmpty()) {
            return false;
        }
        String cleanTarget = target.trim().toLowerCase(Locale.ROOT);
        for (String a : artists) {
            if (compareArtistNames(cleanTarget, a) >= 0.90) {
                return true;
            }
        }
        return false;
    }

    private double calculateFeaturedOverlap(List<String> sourceFeatured, List<String> candFeatured) {
        if (sourceFeatured == null || sourceFeatured.isEmpty() || candFeatured == null || candFeatured.isEmpty()) {
            return 0.0;
        }

        int matches = 0;
        boolean[] usedCandidate = new boolean[candFeatured.size()];

        for (String s : sourceFeatured) {
            for (int j = 0; j < candFeatured.size(); j++) {
                if (usedCandidate[j]) {
                    continue;
                }
                if (compareArtistNames(s, candFeatured.get(j)) >= 0.90) {
                    matches++;
                    usedCandidate[j] = true;
                    break;
                }
            }
        }

        return (double) matches / Math.max(sourceFeatured.size(), candFeatured.size());
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
