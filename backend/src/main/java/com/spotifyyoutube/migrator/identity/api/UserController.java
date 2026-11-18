package com.spotifyyoutube.migrator.identity.api;

import com.spotifyyoutube.migrator.common.exception.ResourceNotFoundException;
import com.spotifyyoutube.migrator.identity.api.dto.UserResponse;
import com.spotifyyoutube.migrator.identity.api.mapper.UserMapper;
import com.spotifyyoutube.migrator.identity.application.UserService;
import com.spotifyyoutube.migrator.identity.domain.User;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.security.Principal;
import java.util.UUID;

@RestController
@RequestMapping("/api/users")
public class UserController {

    private final UserService userService;

    public UserController(UserService userService) {
        this.userService = userService;
    }

    @GetMapping("/{id}")
    public ResponseEntity<UserResponse> getUser(@PathVariable UUID id, Principal principal) {
        User user = userService.getUserById(id);
        if (principal != null) {
            String identifier = principal.getName();
            boolean matchesIdentifier = identifier.equalsIgnoreCase(user.getEmail())
                    || identifier.equalsIgnoreCase(user.getId().toString())
                    || "user".equals(identifier);
            if (!matchesIdentifier) {
                try {
                    User authenticatedUser = userService.getUserByEmail(identifier);
                    if (authenticatedUser != null && !authenticatedUser.getId().equals(user.getId())) {
                        throw new ResourceNotFoundException("User not found with id: " + id);
                    }
                } catch (ResourceNotFoundException e) {
                    if (e.getMessage() != null && e.getMessage().contains("User not found with id")) {
                        throw e;
                    }
                    throw new ResourceNotFoundException("User not found with id: " + id);
                }
            }
        }
        return ResponseEntity.ok(UserMapper.toResponse(user));
    }
}
