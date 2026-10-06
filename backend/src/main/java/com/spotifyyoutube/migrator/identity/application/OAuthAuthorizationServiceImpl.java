package com.spotifyyoutube.migrator.identity.application;

import com.spotifyyoutube.migrator.common.exception.ResourceNotFoundException;
import com.spotifyyoutube.migrator.identity.domain.OAuthProvider;
import com.spotifyyoutube.migrator.identity.domain.OAuthState;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
public class OAuthAuthorizationServiceImpl implements OAuthAuthorizationService {

    private final OAuthStateService stateService;
    private final OAuthConnectionService connectionService;
    private final Map<OAuthProvider, OAuthProviderAdapter> adapters;

    public OAuthAuthorizationServiceImpl(
            OAuthStateService stateService, 
            OAuthConnectionService connectionService,
            List<OAuthProviderAdapter> adapterList) {
        this.stateService = stateService;
        this.connectionService = connectionService;
        this.adapters = adapterList.stream()
                .collect(Collectors.toMap(OAuthProviderAdapter::getProvider, Function.identity()));
    }

    @Override
    public String generateAuthorizationUrl(UUID userId, OAuthProvider provider) {
        OAuthProviderAdapter adapter = getAdapter(provider);
        OAuthState state = stateService.generateState(userId, provider);
        return adapter.getAuthorizationUrl(state.getState());
    }

    @Override
    public void handleCallback(String code, String stateStr, UUID expectedUserId, OAuthProvider provider) {
        // 1. Validate state
        stateService.validateAndConsumeState(stateStr, expectedUserId, provider);

        // 2. Exchange code for token
        OAuthProviderAdapter adapter = getAdapter(provider);
        OAuthTokenResponse tokenResponse = adapter.exchangeCode(code);

        // 3. Fetch provider identity (e.g. Spotify User ID)
        String providerUserId = adapter.getProviderIdentity(tokenResponse.accessToken());

        // 4. Save connection
        connectionService.saveConnection(expectedUserId, provider, providerUserId, tokenResponse);
    }

    private OAuthProviderAdapter getAdapter(OAuthProvider provider) {
        OAuthProviderAdapter adapter = adapters.get(provider);
        if (adapter == null) {
            throw new ResourceNotFoundException("OAuth provider adapter not configured for: " + provider);
        }
        return adapter;
    }
}
