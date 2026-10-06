package com.spotifyyoutube.migrator.identity.application;

import com.spotifyyoutube.migrator.identity.domain.OAuthProvider;
import com.spotifyyoutube.migrator.identity.domain.OAuthState;
import com.spotifyyoutube.migrator.identity.repository.OAuthStateRepository;
import com.spotifyyoutube.migrator.common.exception.InvalidStateException;
import com.spotifyyoutube.migrator.common.exception.ResourceNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.OffsetDateTime;
import java.util.Base64;
import java.util.UUID;

@Service
public class OAuthStateServiceImpl implements OAuthStateService {

    private final OAuthStateRepository oAuthStateRepository;
    private final SecureRandom secureRandom = new SecureRandom();

    public OAuthStateServiceImpl(OAuthStateRepository oAuthStateRepository) {
        this.oAuthStateRepository = oAuthStateRepository;
    }

    @Override
    @Transactional
    public OAuthState generateState(UUID userId, OAuthProvider provider) {
        byte[] randomBytes = new byte[32];
        secureRandom.nextBytes(randomBytes);
        String stateToken = Base64.getUrlEncoder().withoutPadding().encodeToString(randomBytes);

        OAuthState state = new OAuthState();
        state.setState(stateToken);
        state.setUserId(userId);
        state.setProvider(provider);
        state.setCreatedAt(OffsetDateTime.now());
        state.setExpiresAt(OffsetDateTime.now().plusMinutes(15)); // Short-lived

        return oAuthStateRepository.save(state);
    }

    @Override
    @Transactional
    public OAuthState validateAndConsumeState(String stateToken, UUID expectedUserId, OAuthProvider provider) {
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
            throw new InvalidStateException("OAuth state has expired.");
        }

        state.setConsumedAt(OffsetDateTime.now());
        return oAuthStateRepository.save(state);
    }
}
