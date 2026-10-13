package com.spotifyyoutube.migrator.matching.engine.ranking;

import com.spotifyyoutube.migrator.matching.engine.CandidateMatchEvaluation;
import com.spotifyyoutube.migrator.matching.engine.MatchScoreBreakdown;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/**
 * Default implementation of {@link CandidateRankingEngine}.
 * <p>
 * Sorts evaluated candidates using a deterministic, multi-level tie-breaking hierarchy:
 * <ol>
 *   <li><b>Overall Composite Score:</b> Descending order (highest overall score ranks first).</li>
 *   <li><b>Title Similarity Score:</b> Descending order (rewards stronger core title matches).</li>
 *   <li><b>Artist Similarity Score:</b> Descending order (rewards stronger artist alignment).</li>
 *   <li><b>Official Provenance Signal:</b> Descending order (prefers verified official releases over fan uploads).</li>
 *   <li><b>Version Penalty:</b> Ascending order (prefers recordings with fewer/smaller version discrepancies).</li>
 *   <li><b>Duration Alignment Score:</b> Descending order (closer playback length alignment).</li>
 *   <li><b>Candidate Identifier:</b> Natural lexicographical ascending order (guarantees 100% reproducible ordering).</li>
 * </ol>
 * Does not mutate input collections and returns an unmodifiable list.
 */
@Component
public class DefaultCandidateRankingEngine implements CandidateRankingEngine {

    private static final Comparator<CandidateMatchEvaluation> COMPARATOR = (e1, e2) -> {
        if (e1 == e2) {
            return 0;
        }
        if (e1 == null) {
            return 1;
        }
        if (e2 == null) {
            return -1;
        }

        // 1. Overall composite score (descending)
        double score1 = e1.overallScore() != null ? e1.overallScore() : 0.0;
        double score2 = e2.overallScore() != null ? e2.overallScore() : 0.0;
        int scoreCompare = Double.compare(score2, score1);
        if (scoreCompare != 0) {
            return scoreCompare;
        }

        MatchScoreBreakdown b1 = e1.scoreBreakdown();
        MatchScoreBreakdown b2 = e2.scoreBreakdown();

        // 2. Title similarity score (descending)
        double title1 = b1 != null && b1.titleScore() != null ? b1.titleScore() : 0.0;
        double title2 = b2 != null && b2.titleScore() != null ? b2.titleScore() : 0.0;
        int titleCompare = Double.compare(title2, title1);
        if (titleCompare != 0) {
            return titleCompare;
        }

        // 3. Artist similarity score (descending)
        double artist1 = b1 != null && b1.artistScore() != null ? b1.artistScore() : 0.0;
        double artist2 = b2 != null && b2.artistScore() != null ? b2.artistScore() : 0.0;
        int artistCompare = Double.compare(artist2, artist1);
        if (artistCompare != 0) {
            return artistCompare;
        }

        // 4. Official provenance score (descending)
        double off1 = b1 != null && b1.officialSignalScore() != null ? b1.officialSignalScore() : 0.0;
        double off2 = b2 != null && b2.officialSignalScore() != null ? b2.officialSignalScore() : 0.0;
        int offCompare = Double.compare(off2, off1);
        if (offCompare != 0) {
            return offCompare;
        }

        // 5. Version penalty (ascending: lower penalty is preferred)
        double pen1 = b1 != null && b1.versionPenalty() != null ? b1.versionPenalty() : 0.0;
        double pen2 = b2 != null && b2.versionPenalty() != null ? b2.versionPenalty() : 0.0;
        int penCompare = Double.compare(pen1, pen2);
        if (penCompare != 0) {
            return penCompare;
        }

        // 6. Duration alignment score (descending)
        double dur1 = b1 != null && b1.durationScore() != null ? b1.durationScore() : 0.0;
        double dur2 = b2 != null && b2.durationScore() != null ? b2.durationScore() : 0.0;
        int durCompare = Double.compare(dur2, dur1);
        if (durCompare != 0) {
            return durCompare;
        }

        // 7. Stable candidate ID tie-breaker (lexicographical ascending)
        String id1 = e1.candidateId() != null ? e1.candidateId() : "";
        String id2 = e2.candidateId() != null ? e2.candidateId() : "";
        return id1.compareTo(id2);
    };

    @Override
    public List<CandidateMatchEvaluation> rankCandidates(List<CandidateMatchEvaluation> evaluations) {
        Objects.requireNonNull(evaluations, "Candidate evaluations list must not be null");
        if (evaluations.isEmpty()) {
            return List.of();
        }

        List<CandidateMatchEvaluation> copy = new ArrayList<>();
        for (CandidateMatchEvaluation eval : evaluations) {
            if (eval != null) {
                copy.add(eval);
            }
        }

        copy.sort(COMPARATOR);
        return List.copyOf(copy);
    }
}
