package com.spotifyyoutube.migrator.identity.application;

import com.spotifyyoutube.migrator.identity.domain.User;

import java.util.UUID;

public interface UserService {
    
    /**
     * Retrieves a user by their unique identifier.
     * @param id The user ID
     * @return The User entity
     * @throws com.spotifyyoutube.migrator.common.exception.ResourceNotFoundException if user not found
     */
    User getUserById(UUID id);
    
    /**
     * Retrieves a user by their email address.
     * @param email The user's email
     * @return The User entity
     * @throws com.spotifyyoutube.migrator.common.exception.ResourceNotFoundException if user not found
     */
    User getUserByEmail(String email);
}
