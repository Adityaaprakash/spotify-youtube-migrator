package com.spotifyyoutube.migrator.migration.review.application;

import com.spotifyyoutube.migrator.common.exception.InvalidStateException;
import com.spotifyyoutube.migrator.common.exception.ResourceNotFoundException;
import com.spotifyyoutube.migrator.identity.domain.User;
import com.spotifyyoutube.migrator.identity.repository.UserRepository;
import com.spotifyyoutube.migrator.migration.application.destination.model.DestinationTrackCandidate;
import com.spotifyyoutube.migrator.migration.application.plan.MigrationPlan;
import com.spotifyyoutube.migrator.migration.application.plan.MigrationPlanTrack;
import com.spotifyyoutube.migrator.migration.review.domain.ReviewDecisionSource;
import com.spotifyyoutube.migrator.migration.review.domain.ReviewProgressSummary;
import com.spotifyyoutube.migrator.migration.review.domain.ReviewSession;
import com.spotifyyoutube.migrator.migration.review.domain.ReviewTrack;
import com.spotifyyoutube.migrator.migration.review.domain.ReviewTrackStatus;
import com.spotifyyoutube.migrator.migration.review.repository.ReviewSessionRepository;
import com.spotifyyoutube.migrator.migration.review.repository.ReviewTrackRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Default implementation of {@link ReviewSessionService}.
 * <p>
 * Manages the state and lifecycle of migration review sessions.
 * Guarantees that:
 * <ol>
 *   <li>The Phase 9 preview remains strictly read-only and unmutated.</li>
 *   <li>Original automatic matching evidence is preserved without alteration.</li>
 *   <li>Excluded tracks are never approved for migration.</li>
 *   <li>Sessions cannot be completed while unresolved decisions or duplicate conflicts remain.</li>
 *   <li>Zero destination mutations (no YouTube playlists or videos created).</li>
 * </ol>
 */
@Service
@Transactional
public class DefaultReviewSessionService implements ReviewSessionService {

    private static final Logger log = LoggerFactory.getLogger(DefaultReviewSessionService.class);

    private final ReviewSessionRepository reviewSessionRepository;
    private final ReviewTrackRepository reviewTrackRepository;
    private final UserRepository userRepository;

    public DefaultReviewSessionService(
            ReviewSessionRepository reviewSessionRepository,
            ReviewTrackRepository reviewTrackRepository,
            UserRepository userRepository
    ) {
        this.reviewSessionRepository = Objects.requireNonNull(reviewSessionRepository, "ReviewSessionRepository must not be null");
        this.reviewTrackRepository = Objects.requireNonNull(reviewTrackRepository, "ReviewTrackRepository must not be null");
        this.userRepository = Objects.requireNonNull(userRepository, "UserRepository must not be null");
    }

    @Override
    public ReviewSession createSessionFromPlan(UUID userId, MigrationPlan plan) {
        Objects.requireNonNull(userId, "userId must not be null");
        Objects.requireNonNull(plan, "plan must not be null");

        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("User not found with id: " + userId));

        ReviewSession session = new ReviewSession(
                user,
                plan.sourcePlaylistId(),
                plan.sourcePlaylistTitle(),
                plan.planId()
        );

        List<MigrationPlanTrack> planTracks = plan.tracks();
        for (MigrationPlanTrack planTrack : planTracks) {
            ReviewTrack track = new ReviewTrack();
            track.setPosition(planTrack.position());
            track.setSourceTrackId(planTrack.sourceTrackId());
            track.setSourceTitle(planTrack.sourceTitle());
            track.setSourceArtist(planTrack.sourceArtist());
            track.setOriginalClassification(planTrack.classification());

            if (planTrack.selectedCandidate() != null) {
                DestinationTrackCandidate c = planTrack.selectedCandidate();
                track.setOriginalCandidateId(c.id());
                track.setOriginalCandidateTitle(c.title());
                track.setOriginalCandidateChannel(c.channelOrArtist());
                track.setOriginalCandidateDurationMs(c.duration() != null ? c.duration().toMillis() : null);
            }

            track.setOriginalMatchScore(planTrack.matchScore());
            track.setOriginalCalibratedProbability(planTrack.calibratedProbability());
            track.setOriginalMatchDecision(planTrack.matchDecision());

            if (planTrack.matchReasons() != null && !planTrack.matchReasons().isEmpty()) {
                track.setOriginalMatchReasons(String.join("; ", planTrack.matchReasons()));
            }

            // Map initial review decision state from plan classification
            switch (planTrack.classification()) {
                case AUTO_MATCH -> {
                    track.setReviewStatus(ReviewTrackStatus.ACCEPTED);
                    track.setDecisionSource(ReviewDecisionSource.AUTOMATIC);
                    track.setSelectedCandidateId(track.getOriginalCandidateId());
                    track.setSelectedCandidateTitle(track.getOriginalCandidateTitle());
                    track.setSelectedCandidateChannel(track.getOriginalCandidateChannel());
                    track.setSelectedCandidateDurationMs(track.getOriginalCandidateDurationMs());
                    track.setExcluded(false);
                    track.setHasConflict(false);
                }
                case REVIEW_REQUIRED -> {
                    track.setReviewStatus(ReviewTrackStatus.AWAITING_REVIEW);
                    track.setDecisionSource(ReviewDecisionSource.AUTOMATIC);
                    track.setExcluded(false);
                    track.setHasConflict(false);
                }
                case UNMATCHED, FAILED -> {
                    track.setReviewStatus(ReviewTrackStatus.AWAITING_REVIEW);
                    track.setDecisionSource(ReviewDecisionSource.AUTOMATIC);
                    track.setExcluded(false);
                    track.setHasConflict(false);
                }
                case DUPLICATE -> {
                    track.setReviewStatus(ReviewTrackStatus.AWAITING_REVIEW);
                    track.setDecisionSource(ReviewDecisionSource.AUTOMATIC);
                    track.setExcluded(false);
                    track.setHasConflict(true);
                    track.setConflictReason("Duplicate destination candidate: '" + track.getOriginalCandidateId()
                            + "' was already claimed by an earlier track");
                }
                case EXCLUDED -> {
                    track.setReviewStatus(ReviewTrackStatus.EXCLUDED);
                    track.setDecisionSource(ReviewDecisionSource.AUTOMATIC);
                    track.setExcluded(true);
                    track.setHasConflict(false);
                }
            }

            session.addTrack(track);
        }

        reconcileConflicts(session);
        ReviewSession savedSession = reviewSessionRepository.save(session);
        log.info("Created review session {} for user {} from plan {} with {} tracks",
                savedSession.getId(), userId, plan.planId(), savedSession.getTotalTracks());
        return savedSession;
    }

    @Override
    @Transactional(readOnly = true)
    public ReviewSession getSession(UUID sessionId) {
        Objects.requireNonNull(sessionId, "sessionId must not be null");
        return reviewSessionRepository.findById(sessionId)
                .orElseThrow(() -> new ResourceNotFoundException("Review session not found with id: " + sessionId));
    }

    @Override
    @Transactional(readOnly = true)
    public ReviewSession getSessionForUser(UUID sessionId, UUID userId) {
        Objects.requireNonNull(sessionId, "sessionId must not be null");
        Objects.requireNonNull(userId, "userId must not be null");

        ReviewSession session = getSession(sessionId);
        if (!Objects.equals(session.getUser().getId(), userId)) {
            throw new ResourceNotFoundException("Review session not found with id: " + sessionId + " for user: " + userId);
        }
        return session;
    }

    @Override
    @Transactional(readOnly = true)
    public List<ReviewSession> getUserSessions(UUID userId) {
        Objects.requireNonNull(userId, "userId must not be null");
        if (!userRepository.existsById(userId)) {
            throw new ResourceNotFoundException("User not found with id: " + userId);
        }
        return reviewSessionRepository.findByUserIdOrderByCreatedAtDesc(userId);
    }

    @Override
    @Transactional(readOnly = true)
    public ReviewProgressSummary getReviewProgress(UUID sessionId) {
        ReviewSession session = getSession(sessionId);
        return session.deriveSummary();
    }

    @Override
    public ReviewTrack selectCandidate(UUID sessionId, int position, DestinationTrackCandidate candidate, String notes) {
        ReviewSession session = getSession(sessionId);
        if (!session.isInProgress()) {
            throw new InvalidStateException("Cannot modify track: review session is in status " + session.getStatus());
        }

        ReviewTrack track = reviewTrackRepository.findBySessionIdAndPosition(sessionId, position)
                .orElseThrow(() -> new ResourceNotFoundException(String.format(
                        "Track not found at position %d in review session %s", position, sessionId
                )));

        track.selectCandidate(candidate, notes);
        reconcileConflicts(session);
        ReviewTrack savedTrack = reviewTrackRepository.save(track);
        log.debug("Track at position {} in session {} manually matched to candidate {}",
                position, sessionId, candidate.id());
        return savedTrack;
    }

    @Override
    public ReviewTrack acceptAutomaticMatch(UUID sessionId, int position) {
        ReviewSession session = getSession(sessionId);
        if (!session.isInProgress()) {
            throw new InvalidStateException("Cannot modify track: review session is in status " + session.getStatus());
        }

        ReviewTrack track = reviewTrackRepository.findBySessionIdAndPosition(sessionId, position)
                .orElseThrow(() -> new ResourceNotFoundException(String.format(
                        "Track not found at position %d in review session %s", position, sessionId
                )));

        track.acceptOriginalMatch();
        reconcileConflicts(session);
        ReviewTrack savedTrack = reviewTrackRepository.save(track);
        log.debug("Track at position {} in session {} accepted automatic candidate {}",
                position, sessionId, track.getSelectedCandidateId());
        return savedTrack;
    }

    @Override
    public ReviewTrack excludeTrack(UUID sessionId, int position, String reason) {
        ReviewSession session = getSession(sessionId);
        if (!session.isInProgress()) {
            throw new InvalidStateException("Cannot modify track: review session is in status " + session.getStatus());
        }

        ReviewTrack track = reviewTrackRepository.findBySessionIdAndPosition(sessionId, position)
                .orElseThrow(() -> new ResourceNotFoundException(String.format(
                        "Track not found at position %d in review session %s", position, sessionId
                )));

        track.exclude(reason);
        reconcileConflicts(session);
        ReviewTrack savedTrack = reviewTrackRepository.save(track);
        log.debug("Track at position {} in session {} excluded from migration", position, sessionId);
        return savedTrack;
    }

    @Override
    public ReviewSession completeSession(UUID sessionId) {
        ReviewSession session = getSession(sessionId);
        if (!session.isInProgress()) {
            throw new InvalidStateException("Review session is not in progress: " + session.getStatus());
        }

        reconcileConflicts(session);
        ReviewProgressSummary summary = session.deriveSummary();

        // Enforces Invariant 4 & Invariant 5
        session.complete(summary);

        ReviewSession savedSession = reviewSessionRepository.save(session);
        log.info("Review session {} completed successfully with {} total tracks",
                sessionId, savedSession.getTotalTracks());
        return savedSession;
    }

    @Override
    public ReviewSession cancelSession(UUID sessionId) {
        ReviewSession session = getSession(sessionId);
        session.cancel();
        ReviewSession savedSession = reviewSessionRepository.save(session);
        log.info("Review session {} cancelled", sessionId);
        return savedSession;
    }

    /**
     * Reconciles destination video candidate conflicts across active, approved tracks in the session.
     * Prevents duplicate destination additions from going unnoticed.
     */
    private void reconcileConflicts(ReviewSession session) {
        List<ReviewTrack> tracks = session.getTracks();
        if (tracks == null || tracks.isEmpty()) {
            return;
        }

        Set<String> seenCandidateIds = new HashSet<>();
        Map<String, Integer> seenPositions = new HashMap<>();

        for (ReviewTrack track : tracks) {
            if (track.isExcluded()) {
                track.clearConflict();
                continue;
            }

            String candidateId = track.getSelectedCandidateId();
            if (candidateId != null && !candidateId.isBlank()
                    && (track.getReviewStatus() == ReviewTrackStatus.ACCEPTED || track.getReviewStatus() == ReviewTrackStatus.USER_SELECTED)) {
                if (seenCandidateIds.contains(candidateId)) {
                    int prevPos = seenPositions.get(candidateId);
                    track.setConflict("Duplicate destination candidate: '" + candidateId
                            + "' already claimed by earlier track at position " + prevPos);
                } else {
                    seenCandidateIds.add(candidateId);
                    seenPositions.put(candidateId, track.getPosition());
                    if (track.hasConflict() && track.getConflictReason() != null
                            && track.getConflictReason().startsWith("Duplicate destination candidate")) {
                        track.clearConflict();
                    }
                }
            }
        }
    }
}
