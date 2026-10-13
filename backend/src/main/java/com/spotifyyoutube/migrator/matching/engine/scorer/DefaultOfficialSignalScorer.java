package com.spotifyyoutube.migrator.matching.engine.scorer;

import com.spotifyyoutube.migrator.matching.engine.feature.CandidateTrackFeatures;
import com.spotifyyoutube.migrator.matching.engine.feature.OfficialSignalEvidence;
import com.spotifyyoutube.migrator.matching.engine.feature.SourceTrackFeatures;
import org.springframework.stereotype.Component;

import java.util.Objects;

/**
 * Default implementation of {@link OfficialSignalScorer}.
 * <p>
 * Evaluates verified official provenance evidence (auto-generated Topic channels, VEVO syndication,
 * verified artist channel match, distributor release delivery descriptions, and verified audio/video title markers).
 * Strictly observes conservative authenticity rules: unverified claims (e.g. channel names merely containing
 * the word "official") are awarded {@code 0.0}.
 */
@Component
public class DefaultOfficialSignalScorer implements OfficialSignalScorer {

    @Override
    public Double score(SourceTrackFeatures source, CandidateTrackFeatures candidate) {
        Objects.requireNonNull(source, "SourceTrackFeatures must not be null");
        Objects.requireNonNull(candidate, "CandidateTrackFeatures must not be null");

        OfficialSignalEvidence officialSignal = candidate.officialSignal();
        if (officialSignal == null) {
            return null;
        }
        if (officialSignal.hasOfficialEvidence()) {
            return 1.0;
        }

        return 0.0;
    }
}
