package com.spotifyyoutube.migrator.identity.application;

import com.spotifyyoutube.migrator.common.exception.ConflictException;
import com.spotifyyoutube.migrator.common.exception.ResourceNotFoundException;
import com.spotifyyoutube.migrator.identity.domain.User;
import com.spotifyyoutube.migrator.identity.domain.UserStatus;
import com.spotifyyoutube.migrator.identity.repository.UserRepository;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AuthenticationServiceImpl implements AuthenticationService {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;

    public AuthenticationServiceImpl(UserRepository userRepository, PasswordEncoder passwordEncoder) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
    }

    @Override
    @Transactional
    public User register(String email, String password, String displayName) {
        String normalizedEmail = email.toLowerCase().trim();
        
        if (userRepository.findByEmail(normalizedEmail).isPresent()) {
            throw new ConflictException("User with this email already exists.");
        }

        User user = new User(normalizedEmail, passwordEncoder.encode(password), displayName);
        return userRepository.save(user);
    }

    @Override
    @Transactional(readOnly = true)
    public User login(String email, String password) {
        String normalizedEmail = email.toLowerCase().trim();
        
        User user = userRepository.findByEmail(normalizedEmail)
                .orElseThrow(() -> new ResourceNotFoundException("Invalid email or password."));

        if (!passwordEncoder.matches(password, user.getPasswordHash())) {
            throw new ResourceNotFoundException("Invalid email or password.");
        }

        if (user.getStatus() != UserStatus.ACTIVE) {
            throw new IllegalStateException("Account is disabled."); // Should map to a 403 or specific auth exception eventually
        }

        return user;
    }
}
