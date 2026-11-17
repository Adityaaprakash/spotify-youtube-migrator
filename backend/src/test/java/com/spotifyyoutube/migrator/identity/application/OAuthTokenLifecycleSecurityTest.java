package com.spotifyyoutube.migrator.identity.application;

import com.spotifyyoutube.migrator.common.exception.InvalidStateException;
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
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class OAuthTokenLifecycleSecurityTest {

    @Mock
    private OAuthConnectionRepository connectionRepository;

    @Mock
    private OAuthTokenService tokenService;

    @Mock
    private OAuthProviderAdapter googleAdapter;

    private OAuthTokenLifecycleServiceImpl lifecycleService;

    private final UUID userA = UUID.randomUUID();
    private final UUID userB = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        when(googleAdapter.getProvider()).thenReturn(OAuthProvider.GOOGLE);
        lifecycleService = new OAuthTokenLifecycleServiceImpl(connectionRepository, tokenService, List.of(googleAdapter));
    }

    @Test
    void refreshConnectionIfNeeded_ConcurrentRequests_OnlyOneUpstreamCallMade() throws Exception {
        OAuthConnection connection = new OAuthConnection();
        connection.setStatus(ConnectionStatus.CONNECTED);
        connection.setExpiresAt(OffsetDateTime.now().minusMinutes(1)); // Expired
        connection.setAccessToken("enc-old-access");
        connection.setRefreshToken("enc-valid-refresh");

        when(connectionRepository.findByUserIdAndProvider(userA, OAuthProvider.GOOGLE))
                .thenReturn(Optional.of(connection));
        when(tokenService.decryptToken("enc-valid-refresh")).thenReturn("dec-refresh");
        when(googleAdapter.refreshToken("dec-refresh"))
                .thenAnswer(inv -> {
                    // Simulate network latency during token refresh
                    Thread.sleep(50);
                    return new OAuthTokenResponse("new-access-token", "new-refresh-token", 3600, "scopes");
                });
        when(tokenService.encryptToken("new-access-token")).thenReturn("enc-new-access");
        when(tokenService.encryptToken("new-refresh-token")).thenReturn("enc-new-refresh");
        when(tokenService.decryptToken("enc-new-access")).thenReturn("new-access-token");
        when(tokenService.decryptToken("enc-new-refresh")).thenReturn("new-refresh-token");

        int concurrency = 5;
        ExecutorService executor = Executors.newFixedThreadPool(concurrency);
        CountDownLatch startLatch = new CountDownLatch(1);
        List<Future<OAuthConnection>> futures = new ArrayList<>();

        for (int i = 0; i < concurrency; i++) {
            futures.add(executor.submit(() -> {
                startLatch.await();
                return lifecycleService.refreshConnectionIfNeeded(userA, OAuthProvider.GOOGLE);
            }));
        }

        startLatch.countDown();
        executor.shutdown();
        assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));

        for (Future<OAuthConnection> future : futures) {
            OAuthConnection result = future.get();
            assertNotNull(result);
            assertEquals("new-access-token", result.getAccessToken());
        }

        // Upstream adapter must only be called ONCE despite 5 simultaneous concurrent requests
        verify(googleAdapter, times(1)).refreshToken("dec-refresh");
    }

    @Test
    void refreshConnectionIfNeeded_RotatedRefreshToken_PersistsNewRefreshToken() {
        OAuthConnection connection = new OAuthConnection();
        connection.setStatus(ConnectionStatus.CONNECTED);
        connection.setExpiresAt(OffsetDateTime.now().minusSeconds(10));
        connection.setAccessToken("enc-old-access");
        connection.setRefreshToken("enc-old-refresh");

        when(connectionRepository.findByUserIdAndProvider(userA, OAuthProvider.GOOGLE))
                .thenReturn(Optional.of(connection));
        when(tokenService.decryptToken("enc-old-refresh")).thenReturn("old-refresh");
        when(googleAdapter.refreshToken("old-refresh"))
                .thenReturn(new OAuthTokenResponse("new-access", "rotated-refresh", 3600, ""));
        when(tokenService.encryptToken("new-access")).thenReturn("enc-new-access");
        when(tokenService.encryptToken("rotated-refresh")).thenReturn("enc-rotated-refresh");
        when(tokenService.decryptToken("enc-new-access")).thenReturn("new-access");
        when(tokenService.decryptToken("enc-rotated-refresh")).thenReturn("rotated-refresh");

        OAuthConnection result = lifecycleService.refreshConnectionIfNeeded(userA, OAuthProvider.GOOGLE);

        assertEquals("new-access", result.getAccessToken());
        assertEquals("rotated-refresh", result.getRefreshToken());

        ArgumentCaptor<OAuthConnection> captor = ArgumentCaptor.forClass(OAuthConnection.class);
        verify(connectionRepository).save(captor.capture());
        OAuthConnection saved = captor.getValue();
        assertEquals("enc-rotated-refresh", saved.getRefreshToken());
        assertEquals(ConnectionStatus.CONNECTED, saved.getStatus());
    }

    @Test
    void refreshConnectionIfNeeded_NoNewRefreshToken_PreservesExistingRefreshToken() {
        OAuthConnection connection = new OAuthConnection();
        connection.setStatus(ConnectionStatus.CONNECTED);
        connection.setExpiresAt(OffsetDateTime.now().minusSeconds(10));
        connection.setAccessToken("enc-old-access");
        connection.setRefreshToken("enc-existing-refresh");

        when(connectionRepository.findByUserIdAndProvider(userA, OAuthProvider.GOOGLE))
                .thenReturn(Optional.of(connection));
        when(tokenService.decryptToken("enc-existing-refresh")).thenReturn("existing-refresh");
        // Provider returns null refresh token (standard Google behavior on refresh)
        when(googleAdapter.refreshToken("existing-refresh"))
                .thenReturn(new OAuthTokenResponse("new-access", null, 3600, ""));
        when(tokenService.encryptToken("new-access")).thenReturn("enc-new-access");
        when(tokenService.decryptToken("enc-new-access")).thenReturn("new-access");
        when(tokenService.decryptToken("enc-existing-refresh")).thenReturn("existing-refresh");

        OAuthConnection result = lifecycleService.refreshConnectionIfNeeded(userA, OAuthProvider.GOOGLE);

        assertEquals("new-access", result.getAccessToken());
        assertEquals("existing-refresh", result.getRefreshToken());

        ArgumentCaptor<OAuthConnection> captor = ArgumentCaptor.forClass(OAuthConnection.class);
        verify(connectionRepository).save(captor.capture());
        OAuthConnection saved = captor.getValue();
        // Existing refresh token is preserved
        assertEquals("enc-existing-refresh", saved.getRefreshToken());
    }

    @Test
    void refreshConnectionIfNeeded_ConnectionDeletedDuringUpstreamCall_DoesNotUndoDisconnect() {
        UUID connectionId = UUID.randomUUID();
        OAuthConnection connection = new OAuthConnection();
        connection.setId(connectionId);
        connection.setStatus(ConnectionStatus.CONNECTED);
        connection.setExpiresAt(OffsetDateTime.now().minusSeconds(10));
        connection.setAccessToken("enc-old-access");
        connection.setRefreshToken("enc-old-refresh");

        when(connectionRepository.findByUserIdAndProvider(userA, OAuthProvider.GOOGLE))
                .thenReturn(Optional.of(connection));
        when(tokenService.decryptToken("enc-old-refresh")).thenReturn("old-refresh");
        when(googleAdapter.refreshToken("old-refresh"))
                .thenReturn(new OAuthTokenResponse("new-access", "new-refresh", 3600, ""));
        // Simulate disconnect occurring while awaiting upstream response: existsById returns false
        when(connectionRepository.existsById(connectionId)).thenReturn(false);

        InvalidStateException ex = assertThrows(InvalidStateException.class, () ->
                lifecycleService.refreshConnectionIfNeeded(userA, OAuthProvider.GOOGLE));

        assertTrue(ex.getMessage().contains("was disconnected during refresh"));
        // connectionRepository.save must NEVER be called, preserving the deletion!
        verify(connectionRepository, never()).save(any());
    }

    @Test
    void refreshConnectionIfNeeded_UpstreamFailure_FlagsReauthRequired() {
        OAuthConnection connection = new OAuthConnection();
        connection.setStatus(ConnectionStatus.CONNECTED);
        connection.setExpiresAt(OffsetDateTime.now().minusSeconds(10));
        connection.setAccessToken("enc-old-access");
        connection.setRefreshToken("enc-refresh");

        when(connectionRepository.findByUserIdAndProvider(userA, OAuthProvider.GOOGLE))
                .thenReturn(Optional.of(connection));
        when(tokenService.decryptToken("enc-refresh")).thenReturn("refresh");
        when(googleAdapter.refreshToken("refresh"))
                .thenThrow(new InvalidStateException("Failed to exchange Google token: 400"));

        OAuthConnection result = lifecycleService.refreshConnectionIfNeeded(userA, OAuthProvider.GOOGLE);

        assertEquals(ConnectionStatus.REAUTH_REQUIRED, result.getStatus());

        ArgumentCaptor<OAuthConnection> captor = ArgumentCaptor.forClass(OAuthConnection.class);
        verify(connectionRepository).save(captor.capture());
        assertEquals(ConnectionStatus.REAUTH_REQUIRED, captor.getValue().getStatus());
    }

    @Test
    void refreshConnectionIfNeeded_MissingConnection_ThrowsInvalidStateException() {
        when(connectionRepository.findByUserIdAndProvider(userB, OAuthProvider.GOOGLE))
                .thenReturn(Optional.empty());

        assertThrows(InvalidStateException.class, () ->
                lifecycleService.refreshConnectionIfNeeded(userB, OAuthProvider.GOOGLE));
    }

    @Test
    void disconnect_RemovesConnectionFromDatabase() {
        OAuthConnection connection = new OAuthConnection();
        when(connectionRepository.findByUserIdAndProvider(userA, OAuthProvider.GOOGLE))
                .thenReturn(Optional.of(connection));

        lifecycleService.disconnect(userA, OAuthProvider.GOOGLE);

        verify(connectionRepository).delete(connection);
    }
}
