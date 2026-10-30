package com.spotifyyoutube.migrator.migration.api;

import com.spotifyyoutube.migrator.common.exception.ResourceNotFoundException;
import com.spotifyyoutube.migrator.identity.application.UserService;
import com.spotifyyoutube.migrator.identity.domain.User;
import com.spotifyyoutube.migrator.migration.api.dto.ExecutionValidationResponse;
import com.spotifyyoutube.migrator.migration.api.dto.ValidateExecutionRequest;
import com.spotifyyoutube.migrator.migration.api.mapper.MigrationExecutionApiMapper;
import com.spotifyyoutube.migrator.migration.application.execution.ApprovedMigrationExecutionService;
import com.spotifyyoutube.migrator.migration.application.execution.contract.ApprovedMigrationExecutionRequest;
import com.spotifyyoutube.migrator.migration.application.execution.contract.ExecutionValidationResult;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.security.Principal;
import java.util.Objects;
import java.util.UUID;

/**
 * REST controller exposing execution validation and eligibility inspection endpoints for approved migrations.
 * <p>
 * Strictly read-only boundary: validates whether a completed review session is eligible for destination execution
 * without creating playlists or inserting tracks.
 */
@RestController
@RequestMapping({"/api/migrations/execution", "/api/migrations/approved"})
public class MigrationExecutionController {

    private final ApprovedMigrationExecutionService executionService;
    private final UserService userService;

    public MigrationExecutionController(
            ApprovedMigrationExecutionService executionService,
            UserService userService
    ) {
        this.executionService = Objects.requireNonNull(executionService, "ApprovedMigrationExecutionService must not be null");
        this.userService = Objects.requireNonNull(userService, "UserService must not be null");
    }

    /**
     * Validates an approved migration execution request against security context and domain rules.
     */
    @PostMapping("/validate")
    public ResponseEntity<ExecutionValidationResponse> validateExecution(
            @RequestBody @Valid ValidateExecutionRequest request,
            Principal principal
    ) {
        UUID userId = resolveUserId(request.userId(), principal);
        ApprovedMigrationExecutionRequest domainReq = MigrationExecutionApiMapper.toDomainRequest(request, userId);
        ExecutionValidationResult result = executionService.validateExecution(domainReq, userId);
        return ResponseEntity.ok(MigrationExecutionApiMapper.toResponse(request.reviewSessionId(), result));
    }

    /**
     * Inspects the execution eligibility of an approved review session by ID.
     */
    @GetMapping("/eligibility/{sessionId}")
    public ResponseEntity<ExecutionValidationResponse> checkEligibility(
            @PathVariable UUID sessionId,
            Principal principal
    ) {
        UUID userId = resolveUserId(null, principal);
        ExecutionValidationResult result = executionService.validateExecution(sessionId, userId);
        return ResponseEntity.ok(MigrationExecutionApiMapper.toResponse(sessionId, result));
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
