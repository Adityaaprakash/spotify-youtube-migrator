package com.spotifyyoutube.migrator.migration.api;

import com.spotifyyoutube.migrator.common.exception.ResourceNotFoundException;
import com.spotifyyoutube.migrator.identity.application.UserService;
import com.spotifyyoutube.migrator.identity.domain.User;
import com.spotifyyoutube.migrator.migration.api.dto.CreateDestinationPlaylistRequest;
import com.spotifyyoutube.migrator.migration.api.dto.DestinationPlaylistResponse;
import com.spotifyyoutube.migrator.migration.api.dto.ExecutionValidationResponse;
import com.spotifyyoutube.migrator.migration.api.dto.ValidateExecutionRequest;
import com.spotifyyoutube.migrator.migration.api.mapper.MigrationExecutionApiMapper;
import com.spotifyyoutube.migrator.migration.application.execution.ApprovedMigrationExecutionService;
import com.spotifyyoutube.migrator.migration.application.execution.DestinationPlaylistCreationService;
import com.spotifyyoutube.migrator.migration.application.execution.contract.ApprovedMigrationExecutionRequest;
import com.spotifyyoutube.migrator.migration.application.execution.contract.DestinationPlaylistCreationResult;
import com.spotifyyoutube.migrator.migration.application.execution.contract.ExecutionValidationResult;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Autowired;
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
 * REST controller exposing execution validation, eligibility inspection, and destination playlist creation
 * for approved migrations.
 * <p>
 * Enforces security ownership, anti-impersonation, and strict mutation boundaries (creates playlist only;
 * zero track insertion).
 */
@RestController
@RequestMapping({"/api/migrations/execution", "/api/migrations/approved"})
public class MigrationExecutionController {

    private final ApprovedMigrationExecutionService executionService;
    private final DestinationPlaylistCreationService playlistCreationService;
    private final UserService userService;

    @Autowired
    public MigrationExecutionController(
            ApprovedMigrationExecutionService executionService,
            DestinationPlaylistCreationService playlistCreationService,
            UserService userService
    ) {
        this.executionService = Objects.requireNonNull(executionService, "ApprovedMigrationExecutionService must not be null");
        this.playlistCreationService = playlistCreationService;
        this.userService = Objects.requireNonNull(userService, "UserService must not be null");
    }

    public MigrationExecutionController(
            ApprovedMigrationExecutionService executionService,
            UserService userService
    ) {
        this(executionService, null, userService);
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
     * Creates a destination playlist using the validated approved migration context (Phase 11C).
     * Strictly creates the playlist only without inserting tracks.
     */
    @PostMapping("/create-playlist")
    public ResponseEntity<DestinationPlaylistResponse> createDestinationPlaylist(
            @RequestBody @Valid CreateDestinationPlaylistRequest request,
            Principal principal
    ) {
        if (playlistCreationService == null) {
            throw new IllegalStateException("DestinationPlaylistCreationService is not configured");
        }
        UUID userId = resolveUserId(request.userId(), principal);
        ApprovedMigrationExecutionRequest domainReq = MigrationExecutionApiMapper.toDomainRequest(request, userId);
        DestinationPlaylistCreationResult result = playlistCreationService.createDestinationPlaylist(domainReq, userId);
        return ResponseEntity.ok(MigrationExecutionApiMapper.toPlaylistResponse(result));
    }

    /**
     * Convenience endpoint to create a destination playlist by review session ID (Phase 11C).
     */
    @PostMapping("/create-playlist/{sessionId}")
    public ResponseEntity<DestinationPlaylistResponse> createDestinationPlaylistBySessionId(
            @PathVariable UUID sessionId,
            Principal principal
    ) {
        if (playlistCreationService == null) {
            throw new IllegalStateException("DestinationPlaylistCreationService is not configured");
        }
        UUID userId = resolveUserId(null, principal);
        DestinationPlaylistCreationResult result = playlistCreationService.createDestinationPlaylist(sessionId, userId);
        return ResponseEntity.ok(MigrationExecutionApiMapper.toPlaylistResponse(result));
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
