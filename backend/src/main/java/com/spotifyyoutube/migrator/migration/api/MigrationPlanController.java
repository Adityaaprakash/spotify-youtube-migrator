package com.spotifyyoutube.migrator.migration.api;

import com.spotifyyoutube.migrator.common.exception.ResourceNotFoundException;
import com.spotifyyoutube.migrator.identity.application.UserService;
import com.spotifyyoutube.migrator.identity.domain.User;
import com.spotifyyoutube.migrator.migration.api.dto.MigrationPlanPreviewRequest;
import com.spotifyyoutube.migrator.migration.api.dto.MigrationPlanResponse;
import com.spotifyyoutube.migrator.migration.application.plan.MigrationPlanService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.security.Principal;
import java.util.Objects;
import java.util.UUID;

/**
 * REST controller for generating migration plan previews.
 * <p>
 * Exposes endpoint {@code POST /api/migrations/preview} allowing authenticated clients
 * to generate transient, point-in-time migration previews for Spotify playlists
 * without performing any write or mutation operations on the target music service.
 */
@RestController
@RequestMapping("/api/migrations/preview")
public class MigrationPlanController {

    private final MigrationPlanService migrationPlanService;
    private final UserService userService;

    public MigrationPlanController(
            MigrationPlanService migrationPlanService,
            UserService userService
    ) {
        this.migrationPlanService = Objects.requireNonNull(migrationPlanService, "MigrationPlanService must not be null");
        this.userService = Objects.requireNonNull(userService, "UserService must not be null");
    }

    @PostMapping
    public ResponseEntity<MigrationPlanResponse> generatePreview(
            @RequestBody @Valid MigrationPlanPreviewRequest request,
            Principal principal
    ) {
        UUID userId = resolveUserId(request, principal);
        MigrationPlanResponse response = migrationPlanService.generatePreview(userId, request);
        return ResponseEntity.ok(response);
    }

    private UUID resolveUserId(MigrationPlanPreviewRequest request, Principal principal) {
        if (principal == null || principal.getName() == null || principal.getName().isBlank()) {
            throw new IllegalArgumentException("User must be authenticated");
        }

        String identifier = principal.getName();
        User user = userService.getUserByEmail(identifier);
        if (user == null || user.getId() == null) {
            throw new ResourceNotFoundException("User not found for authenticated principal: " + identifier);
        }

        // Security check: caller-supplied userId must not impersonate another user
        if (request != null && request.userId() != null && !user.getId().equals(request.userId())) {
            throw new IllegalArgumentException("Authenticated user does not match the requested user ID");
        }

        return user.getId();
    }
}
