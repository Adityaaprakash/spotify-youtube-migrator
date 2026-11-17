package com.spotifyyoutube.migrator.identity.application;

import com.spotifyyoutube.migrator.common.exception.InvalidStateException;
import com.spotifyyoutube.migrator.common.exception.ResourceNotFoundException;
import com.spotifyyoutube.migrator.identity.domain.OAuthProvider;
import com.spotifyyoutube.migrator.identity.domain.OAuthState;
import com.spotifyyoutube.migrator.identity.repository.OAuthStateRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.OffsetDateTime;
import java.util.Base64;
import java.util.UUID;

@Service
public class OAuthStateServiceImpl implements OAuthStateService {

    private static final int STATE_BYTE_LENGTH = 32; // 256 bits of cryptographic entropy
    private static final int EXPIRY_MINUTES = 15;

    private final OAuthStateRepository oAuthStateRepository;
    private final SecureRandom secureRandom = new SecureRandom();

    public OAuthStateServiceImpl(OAuthStateRepository oAuthStateRepository) {
        this.oAuthStateRepository = oAuthStateRepository;
    }

    @Override
    @Transactional
    public OAuthState generateState(UUID userId, OAuthProvider provider) {
        if (userId == null) {
            throw new IllegalArgumentException("User ID must not be null when generating OAuth state.");
        }
        if (provider == null) {
            throw new IllegalArgumentException("Provider must not be null when generating OAuth state.");
        }

        byte[] randomBytes = new byte[STATE_BYTE_LENGTH];
        secureRandom.nextBytes(randomBytes);
        String stateToken = Base64.getUrlEncoder().withoutPadding().encodeToString(randomBytes);

        OAuthState state = new OAuthState();
        state.setState(stateToken);
        state.setUserId(userId);
        state.setProvider(provider);
        state.setCreatedAt(OffsetDateTime.now());
        state.setExpiresAt(OffsetDateTime.now().plusMinutes(EXPIRY_MINUTES));

        return oAuthStateRepository.save(state);
    }

    @Override
    @Transactional
    public OAuthState validateAndConsumeState(String stateToken, UUID expectedUserId, OAuthProvider provider) {
        if (stateToken == null || stateToken.isBlank()) {
            throw new InvalidStateException("OAuth state token must not be null or blank.");
        }
        if (expectedUserId == null) {
            throw new InvalidStateException("Expected user ID must not be null.");
        }
        if (provider == null) {
            throw new InvalidStateException("Expected OAuth provider must not be null.");
        }

        OAuthState state = oAuthStateRepository.findByState(stateToken)
                .orElseThrow(() -> new ResourceNotFoundException("OAuth state not found or invalid."));

        if (!state.getUserId().equals(expectedUserId)) {
            throw new InvalidStateException("OAuth state does not match the authenticated user.");
        }
        
        if (state.getProvider() != provider) {
            throw new InvalidStateException("OAuth state provider mismatch.");
        }

        if (state.isConsumed()) {
            throw new InvalidStateException("OAuth state has already been consumed.");
        }

        if (state.isExpired()) {
            // State has expired, clean it up
            oAuthStateRepository.consumeState(stateToken);
            throw new InvalidStateException("OAuth state has expired.");
        }

        int rowsAffected = oAuthStateRepository.consumeState(stateToken);
        if (rowsAffected == 0) {
            throw new InvalidStateException("OAuth state has already been consumed.");
        }
        
        return state;
    }

    @Override
    @Transactional
    public void invalidateState(String stateToken) {
        if (stateToken != null && !stateToken.isBlank()) {
            oAuthStateRepository.consumeState(stateToken);
        }
    }

    @Override
    @Transactional
    public int purgeExpiredStates() {
        return oAuthStateRepository.deleteExpiredStates(OffsetDateTime.now());
    }
}
