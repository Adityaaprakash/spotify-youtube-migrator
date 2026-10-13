package com.spotifyyoutube.migrator.matching.engine;

/**
 * Top-level orchestration interface for the track matching engine.
 * Coordinates feature extraction, component similarity scoring, recording version penalty evaluation,
 * composite scoring, candidate ranking, and match decision logic.
 */
public interface TrackMatchingEngine {

    /**
     * Executes the complete matching pipeline for a source track and candidate collection.
     *
     * @param request input request holding source track and destination candidates, must not be null
     * @return immutable result containing match decision, selected candidate, score breakdown, and alternatives
     * @throws NullPointerException if request is null
     */
    TrackMatchResult matchTrack(TrackMatchRequest request);
}
