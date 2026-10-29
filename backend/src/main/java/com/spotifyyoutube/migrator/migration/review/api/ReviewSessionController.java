package com.spotifyyoutube.migrator.migration.review.api;

import com.spotifyyoutube.migrator.common.exception.ResourceNotFoundException;
import com.spotifyyoutube.migrator.identity.application.UserService;
import com.spotifyyoutube.migrator.identity.domain.User;
import com.spotifyyoutube.migrator.migration.application.plan.MigrationPlan;
import com.spotifyyoutube.migrator.migration.application.plan.MigrationPlanService;
import com.spotifyyoutube.migrator.migration.review.api.dto.CreateReviewSessionRequest;
import com.spotifyyoutube.migrator.migration.review.api.dto.ExcludeTrackRequest;
import com.spotifyyoutube.migrator.migration.review.api.dto.ReviewCandidateSearchApiRequest;
import com.spotifyyoutube.migrator.migration.review.api.dto.ReviewCandidateSearchResultResponse;
import com.spotifyyoutube.migrator.migration.review.api.dto.ReviewProgressSummaryResponse;
import com.spotifyyoutube.migrator.migration.review.api.dto.ReviewSessionResponse;
import com.spotifyyoutube.migrator.migration.review.api.dto.ReviewTrackResponse;
import com.spotifyyoutube.migrator.migration.review.api.dto.SelectCandidateRequest;
import com.spotifyyoutube.migrator.migration.review.api.dto.SubmitReviewDecisionRequest;
import com.spotifyyoutube.migrator.migration.review.api.mapper.ReviewApiMapper;
import com.spotifyyoutube.migrator.migration.review.application.ReviewCandidateSearchRequest;
import com.spotifyyoutube.migrator.migration.review.application.ReviewCandidateSearchResult;
import com.spotifyyoutube.migrator.migration.review.application.ReviewSessionService;
import com.spotifyyoutube.migrator.migration.review.domain.ReviewProgressSummary;
import com.spotifyyoutube.migrator.migration.review.domain.ReviewSession;
import com.spotifyyoutube.migrator.migration.review.domain.ReviewTrack;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;

import java.security.Principal;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * REST controller exposing Phase 10 review and correction capabilities.
 * <p>
 * Supports asynchronous, multi-step human review of playlist migration plans:
 * <ul>
 *   <li>Creating and retrieving review sessions and itemized tracks</li>
 *   <li>Monitoring real-time reconciled review progress summaries</li>
 *   <li>Searching for alternative destination candidate matches via automated or explicit queries</li>
 *   <li>Recording human decisions (accepting automatic matches, selecting replacements, excluding tracks)</li>
 *   <li>Completing or cancelling review sessions</li>
 * </ul>
 */
@RestController
@RequestMapping({"/api/review-sessions", "/api/reviews"})
public class ReviewSessionController {

    private final ReviewSessionService reviewSessionService;
    private final MigrationPlanService migrationPlanService;
    private final UserService userService;

    public ReviewSessionController(
            ReviewSessionService reviewSessionService,
            MigrationPlanService migrationPlanService,
            UserService userService
    ) {
        this.reviewSessionService = Objects.requireNonNull(reviewSessionService, "ReviewSessionService must not be null");
        this.migrationPlanService = Objects.requireNonNull(migrationPlanService, "MigrationPlanService must not be null");
        this.userService = Objects.requireNonNull(userService, "UserService must not be null");
    }

    /**
     * Creates a new review session from a source playlist migration plan.
     */
    @PostMapping
    public ResponseEntity<ReviewSessionResponse> createReviewSession(
            @RequestBody @Valid CreateReviewSessionRequest request,
            Principal principal
    ) {
        UUID userId = resolveUserId(request.userId(), principal);
        MigrationPlan plan = migrationPlanService.generatePlan(userId, request.sourcePlaylistId(), request.isDeduplicate());
        ReviewSession session = reviewSessionService.createSessionFromPlan(userId, plan);
        ReviewProgressSummary summary = session.deriveSummary();
        return ResponseEntity.status(HttpStatus.CREATED).body(ReviewApiMapper.toSessionResponse(session, summary));
    }

    /**
     * Retrieves all review sessions owned by the authenticated user, ordered newest first.
     */
    @GetMapping
    public ResponseEntity<List<ReviewSessionResponse>> getUserSessions(Principal principal) {
        UUID userId = resolveUserId(null, principal);
        List<ReviewSession> sessions = reviewSessionService.getUserSessions(userId);
        return ResponseEntity.ok(ReviewApiMapper.toSessionResponseList(sessions, reviewSessionService));
    }

    /**
     * Retrieves a single review session by ID.
     */
    @GetMapping("/{id}")
    public ResponseEntity<ReviewSessionResponse> getReviewSession(
            @PathVariable UUID id,
            Principal principal
    ) {
        UUID userId = resolveUserId(null, principal);
        ReviewSession session = reviewSessionService.getSessionForUser(id, userId);
        ReviewProgressSummary summary = reviewSessionService.getReviewProgressForUser(id, userId);
        return ResponseEntity.ok(ReviewApiMapper.toSessionResponse(session, summary));
    }

    /**
     * Retrieves the real-time reconciled review progress summary for a session.
     */
    @GetMapping("/{id}/progress")
    public ResponseEntity<ReviewProgressSummaryResponse> getReviewProgress(
            @PathVariable UUID id,
            Principal principal
    ) {
        UUID userId = resolveUserId(null, principal);
        ReviewProgressSummary summary = reviewSessionService.getReviewProgressForUser(id, userId);
        return ResponseEntity.ok(ReviewApiMapper.toProgressResponse(summary));
    }

    /**
     * Retrieves all review tracks for a session in strict playlist position order (0-indexed).
     */
    @GetMapping("/{id}/tracks")
    public ResponseEntity<List<ReviewTrackResponse>> getReviewTracks(
            @PathVariable UUID id,
            Principal principal
    ) {
        UUID userId = resolveUserId(null, principal);
        List<ReviewTrack> tracks = reviewSessionService.getTracksForUser(id, userId);
        return ResponseEntity.ok(ReviewApiMapper.toTrackResponseList(tracks));
    }

    /**
     * Retrieves a single review track by session ID and 0-indexed position.
     */
    @GetMapping("/{id}/tracks/{position}")
    public ResponseEntity<ReviewTrackResponse> getReviewTrack(
            @PathVariable UUID id,
            @PathVariable int position,
            Principal principal
    ) {
        UUID userId = resolveUserId(null, principal);
        ReviewTrack track = reviewSessionService.getTrackForUser(id, position, userId);
        return ResponseEntity.ok(ReviewApiMapper.toTrackResponse(track));
    }

    /**
     * Searches for alternative destination candidates for a review track.
     */
    @PostMapping("/{id}/tracks/{position}/candidates/search")
    public ResponseEntity<ReviewCandidateSearchResultResponse> searchCandidates(
            @PathVariable UUID id,
            @PathVariable int position,
            @RequestBody(required = false) @Valid ReviewCandidateSearchApiRequest request,
            Principal principal
    ) {
        UUID userId = resolveUserId(null, principal);
        ReviewCandidateSearchRequest searchRequest = (request != null && (request.query() != null || request.limit() != null))
                ? new ReviewCandidateSearchRequest(request.query(), request.limit())
                : ReviewCandidateSearchRequest.defaultSearch();
        ReviewCandidateSearchResult result = reviewSessionService.searchCandidates(id, position, searchRequest, userId);
        return ResponseEntity.ok(ReviewApiMapper.toSearchResultResponse(result));
    }

    /**
     * Submits a review decision (accept, select candidate, or exclude) for a track.
     */
    @RequestMapping(value = "/{id}/tracks/{position}/decision", method = {RequestMethod.PUT, RequestMethod.POST})
    public ResponseEntity<ReviewTrackResponse> submitDecision(
            @PathVariable UUID id,
            @PathVariable int position,
            @RequestBody @Valid SubmitReviewDecisionRequest request,
            Principal principal
    ) {
        UUID userId = resolveUserId(null, principal);
        ReviewTrack track = switch (request.decisionType()) {
            case ACCEPT -> reviewSessionService.acceptAutomaticMatch(id, position, userId);
            case SELECT_CANDIDATE -> reviewSessionService.selectCandidate(
                    id,
                    position,
                    request.candidate().toDomain(),
                    request.notes(),
                    userId
            );
            case EXCLUDE -> reviewSessionService.excludeTrack(id, position, request.notes(), userId);
        };
        return ResponseEntity.ok(ReviewApiMapper.toTrackResponse(track));
    }

    /**
     * Accepts the original automatic matching candidate for a track.
     */
    @PostMapping("/{id}/tracks/{position}/accept")
    public ResponseEntity<ReviewTrackResponse> acceptAutomaticMatch(
            @PathVariable UUID id,
            @PathVariable int position,
            Principal principal
    ) {
        UUID userId = resolveUserId(null, principal);
        ReviewTrack track = reviewSessionService.acceptAutomaticMatch(id, position, userId);
        return ResponseEntity.ok(ReviewApiMapper.toTrackResponse(track));
    }

    /**
     * Manually selects a replacement candidate for a track.
     */
    @PostMapping("/{id}/tracks/{position}/select")
    public ResponseEntity<ReviewTrackResponse> selectCandidate(
            @PathVariable UUID id,
            @PathVariable int position,
            @RequestBody @Valid SelectCandidateRequest request,
            Principal principal
    ) {
        UUID userId = resolveUserId(null, principal);
        ReviewTrack track = reviewSessionService.selectCandidate(
                id,
                position,
                request.candidate().toDomain(),
                request.notes(),
                userId
        );
        return ResponseEntity.ok(ReviewApiMapper.toTrackResponse(track));
    }

    /**
     * Explicitly excludes a track from destination migration.
     */
    @PostMapping("/{id}/tracks/{position}/exclude")
    public ResponseEntity<ReviewTrackResponse> excludeTrack(
            @PathVariable UUID id,
            @PathVariable int position,
            @RequestBody(required = false) ExcludeTrackRequest request,
            Principal principal
    ) {
        UUID userId = resolveUserId(null, principal);
        String reason = request != null ? request.reason() : null;
        ReviewTrack track = reviewSessionService.excludeTrack(id, position, reason, userId);
        return ResponseEntity.ok(ReviewApiMapper.toTrackResponse(track));
    }

    /**
     * Finalizes and completes a review session if all decisions and conflicts are resolved.
     */
    @PostMapping("/{id}/complete")
    public ResponseEntity<ReviewSessionResponse> completeSession(
            @PathVariable UUID id,
            Principal principal
    ) {
        UUID userId = resolveUserId(null, principal);
        ReviewSession session = reviewSessionService.completeSession(id, userId);
        ReviewProgressSummary summary = session.deriveSummary();
        return ResponseEntity.ok(ReviewApiMapper.toSessionResponse(session, summary));
    }

    /**
     * Cancels or abandons an in-progress review session.
     */
    @PostMapping("/{id}/cancel")
    public ResponseEntity<ReviewSessionResponse> cancelSession(
            @PathVariable UUID id,
            Principal principal
    ) {
        UUID userId = resolveUserId(null, principal);
        ReviewSession session = reviewSessionService.cancelSession(id, userId);
        ReviewProgressSummary summary = session.deriveSummary();
        return ResponseEntity.ok(ReviewApiMapper.toSessionResponse(session, summary));
    }

    private UUID resolveUserId(UUID requestedUserId, Principal principal) {
        if (principal == null || principal.getName() == null || principal.getName().isBlank()) {
            throw new IllegalArgumentException("User must be authenticated");
        }

        String identifier = principal.getName();
        User user;
        try {
            user = userService.getUserByEmail(identifier);
        } catch (ResourceNotFoundException e) {
            try {
                user = userService.getUserById(UUID.fromString(identifier));
            } catch (Exception ex) {
                throw new ResourceNotFoundException("User not found for authenticated principal: " + identifier);
            }
        }

        if (user == null || user.getId() == null) {
            throw new ResourceNotFoundException("User not found for authenticated principal: " + identifier);
        }

        // Security check: caller-supplied userId must not impersonate another user
        if (requestedUserId != null && !user.getId().equals(requestedUserId)) {
            throw new IllegalArgumentException("Authenticated user does not match the requested user ID");
        }

        return user.getId();
    }
}
