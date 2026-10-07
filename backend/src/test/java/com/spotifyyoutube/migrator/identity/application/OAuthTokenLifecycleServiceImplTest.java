package com.spotifyyoutube.migrator.identity.application;

import com.spotifyyoutube.migrator.identity.domain.ConnectionStatus;
import com.spotifyyoutube.migrator.identity.domain.OAuthConnection;
import com.spotifyyoutube.migrator.identity.domain.OAuthProvider;
import com.spotifyyoutube.migrator.identity.repository.OAuthConnectionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class OAuthTokenLifecycleServiceImplTest {

    @Mock
    private OAuthConnectionRepository connectionRepository;

    @Mock
    private OAuthTokenService tokenService;

    @Mock
    private OAuthProviderAdapter spotifyAdapter;

    private OAuthTokenLifecycleServiceImpl lifecycleService;

    private final UUID userId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        when(spotifyAdapter.getProvider()).thenReturn(OAuthProvider.SPOTIFY);
        lifecycleService = new OAuthTokenLifecycleServiceImpl(connectionRepository, tokenService, List.of(spotifyAdapter));
    }

    @Test
    void refreshConnectionIfNeeded_ValidToken_ReturnsDecrypted() {
        OAuthConnection connection = new OAuthConnection();
        connection.setStatus(ConnectionStatus.CONNECTED);
        connection.setExpiresAt(OffsetDateTime.now().plusHours(1));
        connection.setAccessToken("enc_acc");
        connection.setRefreshToken("enc_ref");

        when(connectionRepository.findByUserIdAndProvider(userId, OAuthProvider.SPOTIFY)).thenReturn(Optional.of(connection));
        when(tokenService.decryptToken("enc_acc")).thenReturn("acc");
        when(tokenService.decryptToken("enc_ref")).thenReturn("ref");

        OAuthConnection result = lifecycleService.refreshConnectionIfNeeded(userId, OAuthProvider.SPOTIFY);

        assertEquals("acc", result.getAccessToken());
        assertEquals("ref", result.getRefreshToken());
        verify(spotifyAdapter, never()).refreshToken(any());
    }

    @Test
    void refreshConnectionIfNeeded_ExpiredToken_RefreshesAndPersists() {
        OAuthConnection connection = new OAuthConnection();
        connection.setStatus(ConnectionStatus.CONNECTED);
        connection.setExpiresAt(OffsetDateTime.now().minusMinutes(5));
        connection.setAccessToken("enc_old_acc");
        connection.setRefreshToken("enc_ref");

        when(connectionRepository.findByUserIdAndProvider(userId, OAuthProvider.SPOTIFY)).thenReturn(Optional.of(connection));
        when(tokenService.decryptToken("enc_ref")).thenReturn("ref");
        when(spotifyAdapter.refreshToken("ref"))
                .thenReturn(new OAuthTokenResponse("new_acc", "new_ref", 3600, ""));
        when(tokenService.encryptToken("new_acc")).thenReturn("enc_new_acc");
        when(tokenService.encryptToken("new_ref")).thenReturn("enc_new_ref");
        when(tokenService.decryptToken("enc_new_acc")).thenReturn("new_acc");
        when(tokenService.decryptToken("enc_new_ref")).thenReturn("new_ref");

        OAuthConnection result = lifecycleService.refreshConnectionIfNeeded(userId, OAuthProvider.SPOTIFY);

        assertEquals("new_acc", result.getAccessToken());
        assertEquals("new_ref", result.getRefreshToken());

        ArgumentCaptor<OAuthConnection> captor = ArgumentCaptor.forClass(OAuthConnection.class);
        verify(connectionRepository).save(captor.capture());
        OAuthConnection saved = captor.getValue();
        assertEquals("enc_new_acc", saved.getAccessToken());
        assertEquals(ConnectionStatus.CONNECTED, saved.getStatus());
    }

    @Test
    void refreshConnectionIfNeeded_RefreshFailure_SetsReauthRequired() {
        OAuthConnection connection = new OAuthConnection();
        connection.setStatus(ConnectionStatus.CONNECTED);
        connection.setExpiresAt(OffsetDateTime.now().minusMinutes(5));
        connection.setAccessToken("enc_old_acc");
        connection.setRefreshToken("enc_ref");

        when(connectionRepository.findByUserIdAndProvider(userId, OAuthProvider.SPOTIFY)).thenReturn(Optional.of(connection));
        when(tokenService.decryptToken("enc_ref")).thenReturn("ref");
        when(spotifyAdapter.refreshToken("ref")).thenThrow(new RuntimeException("invalid_grant"));

        OAuthConnection result = lifecycleService.refreshConnectionIfNeeded(userId, OAuthProvider.SPOTIFY);

        assertEquals(ConnectionStatus.REAUTH_REQUIRED, result.getStatus());
        verify(connectionRepository).save(connection);
    }

    @Test
    void disconnect_DeletesConnection() {
        OAuthConnection connection = new OAuthConnection();
        when(connectionRepository.findByUserIdAndProvider(userId, OAuthProvider.SPOTIFY)).thenReturn(Optional.of(connection));

        lifecycleService.disconnect(userId, OAuthProvider.SPOTIFY);

        verify(connectionRepository).delete(connection);
    }
}
