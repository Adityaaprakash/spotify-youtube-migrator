package com.spotifyyoutube.migrator.migration.application.execution;

import com.spotifyyoutube.migrator.common.exception.ResourceNotFoundException;
import com.spotifyyoutube.migrator.identity.domain.User;
import com.spotifyyoutube.migrator.migration.application.execution.contract.ApprovedMigrationContext;
import com.spotifyyoutube.migrator.migration.application.execution.contract.ApprovedMigrationExecutionRequest;
import com.spotifyyoutube.migrator.migration.application.execution.contract.ApprovedTrack;
import com.spotifyyoutube.migrator.migration.application.execution.contract.ExecutionValidationResult;
import com.spotifyyoutube.migrator.migration.application.execution.contract.MigrationExecutionValidationException;
import com.spotifyyoutube.migrator.migration.application.execution.contract.ValidationFailureCode;
import com.spotifyyoutube.migrator.migration.application.execution.validation.ApprovedPlanValidator;
import com.spotifyyoutube.migrator.migration.review.domain.ReviewDecisionSource;
import com.spotifyyoutube.migrator.migration.review.domain.ReviewSession;
import com.spotifyyoutube.migrator.migration.review.domain.ReviewSessionStatus;
import com.spotifyyoutube.migrator.migration.review.domain.ReviewTrack;
import com.spotifyyoutube.migrator.migration.review.repository.ReviewSessionRepository;
import com.spotifyyoutube.migrator.migration.review.repository.ReviewTrackRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("Phase 11A: Approved Migration Execution Service Test")
class ApprovedMigrationExecutionServiceTest {

    @Mock
    private ReviewSessionRepository reviewSessionRepository;

    @Mock
    private ReviewTrackRepository reviewTrackRepository;

    @Mock
    private ApprovedPlanValidator approvedPlanValidator;

    @InjectMocks
    private DefaultApprovedMigrationExecutionService executionService;

    private UUID userId;
    private User testUser;
    private UUID sessionId;
    private ReviewSession session;

    @BeforeEach
    void setUp() {
        userId = UUID.randomUUID();
        testUser = new User("user@example.com");
        testUser.setId(userId);

        sessionId = UUID.randomUUID();
        session = new ReviewSession(testUser, "sp-100", "Top 50", UUID.randomUUID());
        session.setId(sessionId);
    }

    @Test
    @DisplayName("validateExecution succeeds and delegates to validator for legitimate owner")
    void validateExecutionSuccess() {
        ApprovedMigrationExecutionRequest req = ApprovedMigrationExecutionRequest.of(sessionId, userId);
        ApprovedMigrationContext expectedContext = new ApprovedMigrationContext(
                sessionId, session.getPlanId(), userId, "sp-100", "Top 50", "Migrated - Top 50",
                null, true, List.of(), 0, 0, 0, Instant.now()
        );
        ExecutionValidationResult expectedResult = ExecutionValidationResult.valid(expectedContext);

        when(reviewSessionRepository.findById(sessionId)).thenReturn(Optional.of(session));
        when(approvedPlanValidator.validate(eq(session), eq(req), eq(userId))).thenReturn(expectedResult);

        ExecutionValidationResult result = executionService.validateExecution(req, userId);

        assertThat(result.isValid()).isTrue();
        assertThat(result.getApprovedContext()).contains(expectedContext);
        verify(approvedPlanValidator).validate(session, req, userId);
    }

    @Test
    @DisplayName("validateExecution fails with MISSING_CONTEXT when session does not exist")
    void validateExecutionSessionNotFound() {
        UUID unknownId = UUID.randomUUID();
        ApprovedMigrationExecutionRequest req = ApprovedMigrationExecutionRequest.of(unknownId, userId);

        when(reviewSessionRepository.findById(unknownId)).thenReturn(Optional.empty());

        ExecutionValidationResult result = executionService.validateExecution(req, userId);

        assertThat(result.isValid()).isFalse();
        assertThat(result.errors().getFirst().code()).isEqualTo(ValidationFailureCode.MISSING_CONTEXT);
        assertThat(result.firstErrorMessage()).contains("Review session not found");
    }

    @Test
    @DisplayName("validateExecution fails with UNAUTHORIZED when session belongs to different user (anti-IDOR)")
    void validateExecutionUnauthorized() {
        UUID otherUserId = UUID.randomUUID();
        User otherUser = new User("other@example.com");
        otherUser.setId(otherUserId);
        session.setUser(otherUser);

        ApprovedMigrationExecutionRequest req = ApprovedMigrationExecutionRequest.of(sessionId, userId);
        when(reviewSessionRepository.findById(sessionId)).thenReturn(Optional.of(session));

        ExecutionValidationResult result = executionService.validateExecution(req, userId);

        assertThat(result.isValid()).isFalse();
        assertThat(result.errors().getFirst().code()).isEqualTo(ValidationFailureCode.UNAUTHORIZED);
        assertThat(result.firstErrorMessage()).contains("not authorized to execute review session");
    }

    @Test
    @DisplayName("validateExecution by ID overload works as expected")
    void validateExecutionByIdOverload() {
        when(reviewSessionRepository.findById(sessionId)).thenReturn(Optional.of(session));
        when(approvedPlanValidator.validate(eq(session), any(ApprovedMigrationExecutionRequest.class), eq(userId)))
                .thenReturn(ExecutionValidationResult.invalid(ValidationFailureCode.INCOMPLETE_REVIEW, "In progress"));

        ExecutionValidationResult result = executionService.validateExecution(sessionId, userId);

        assertThat(result.isValid()).isFalse();
        assertThat(result.errors().getFirst().code()).isEqualTo(ValidationFailureCode.INCOMPLETE_REVIEW);
    }

    @Test
    @DisplayName("prepareExecution returns ApprovedMigrationContext on valid execution")
    void prepareExecutionSuccess() {
        ApprovedMigrationExecutionRequest req = ApprovedMigrationExecutionRequest.of(sessionId, userId);
        ApprovedMigrationContext expectedContext = new ApprovedMigrationContext(
                sessionId, session.getPlanId(), userId, "sp-100", "Top 50", "Migrated - Top 50",
                null, true, List.of(), 0, 0, 0, Instant.now()
        );

        when(reviewSessionRepository.findById(sessionId)).thenReturn(Optional.of(session));
        when(approvedPlanValidator.validateAndApprove(eq(session), eq(req), eq(userId))).thenReturn(expectedContext);

        ApprovedMigrationContext context = executionService.prepareExecution(req, userId);

        assertThat(context).isEqualTo(expectedContext);
    }

    @Test
    @DisplayName("prepareExecution throws ResourceNotFoundException when session belongs to another user (anti-IDOR)")
    void prepareExecutionUnauthorizedThrowsNotFound() {
        UUID otherUserId = UUID.randomUUID();
        User otherUser = new User("other@example.com");
        otherUser.setId(otherUserId);
        session.setUser(otherUser);

        ApprovedMigrationExecutionRequest req = ApprovedMigrationExecutionRequest.of(sessionId, userId);
        when(reviewSessionRepository.findById(sessionId)).thenReturn(Optional.of(session));

        assertThatThrownBy(() -> executionService.prepareExecution(req, userId))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining("Review session not found with id: " + sessionId);
    }

    @Test
    @DisplayName("prepareExecution throws ResourceNotFoundException when session does not exist")
    void prepareExecutionNotFound() {
        UUID unknownId = UUID.randomUUID();
        ApprovedMigrationExecutionRequest req = ApprovedMigrationExecutionRequest.of(unknownId, userId);
        when(reviewSessionRepository.findById(unknownId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> executionService.prepareExecution(req, userId))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    @DisplayName("prepareExecution propagates MigrationExecutionValidationException on validation failure")
    void prepareExecutionThrowsValidationException() {
        ApprovedMigrationExecutionRequest req = ApprovedMigrationExecutionRequest.of(sessionId, userId);
        when(reviewSessionRepository.findById(sessionId)).thenReturn(Optional.of(session));
        when(approvedPlanValidator.validateAndApprove(eq(session), eq(req), eq(userId)))
                .thenThrow(new MigrationExecutionValidationException(
                        ExecutionValidationResult.invalid(ValidationFailureCode.INCOMPLETE_REVIEW, "Review in progress")
                ));

        assertThatThrownBy(() -> executionService.prepareExecution(req, userId))
                .isInstanceOf(MigrationExecutionValidationException.class)
                .hasMessageContaining("Review in progress");
    }

    @Test
    @DisplayName("Lazy loads tracks from repository if session tracks are empty")
    void lazyLoadsTracksFromRepository() {
        ApprovedMigrationExecutionRequest req = ApprovedMigrationExecutionRequest.of(sessionId, userId);
        session.setTotalTracks(2); // totalTracks is 2, but tracks list is empty!

        ReviewTrack track0 = new ReviewTrack();
        track0.setPosition(0);
        track0.setSourceTrackId("sp-0");
        ReviewTrack track1 = new ReviewTrack();
        track1.setPosition(1);
        track1.setSourceTrackId("sp-1");

        when(reviewSessionRepository.findById(sessionId)).thenReturn(Optional.of(session));
        when(reviewTrackRepository.findBySessionIdOrderByPositionAsc(sessionId)).thenReturn(List.of(track0, track1));
        when(approvedPlanValidator.validate(eq(session), eq(req), eq(userId)))
                .thenReturn(ExecutionValidationResult.invalid(ValidationFailureCode.INCOMPLETE_REVIEW, "Still in progress"));

        executionService.validateExecution(req, userId);

        verify(reviewTrackRepository).findBySessionIdOrderByPositionAsc(sessionId);
        assertThat(session.getTracks()).hasSize(2);
    }
}
