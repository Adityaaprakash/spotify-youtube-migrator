package com.spotifyyoutube.migrator.identity.infrastructure.provider;

import com.spotifyyoutube.migrator.common.exception.InvalidStateException;
import com.spotifyyoutube.migrator.identity.application.OAuthTokenResponse;
import com.spotifyyoutube.migrator.identity.domain.OAuthProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestTemplate;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;

class SpotifyProviderAdapterTest {

    private SpotifyProviderAdapter adapter;
    private MockRestServiceServer mockServer;

    @BeforeEach
    void setUp() {
        RestTemplate restTemplate = new RestTemplate();
        mockServer = MockRestServiceServer.createServer(restTemplate);
        
        // Since RestClient is immutable and we can just pass the RestTemplate as a backend
        RestClient.Builder restClientBuilder = RestClient.builder()
                .messageConverters(converters -> converters.addAll(restTemplate.getMessageConverters()))
                .requestFactory(restTemplate.getRequestFactory());

        adapter = new SpotifyProviderAdapter(
                "client123",
                "secret456",
                "http://callback",
                "scope1 scope2",
                restClientBuilder
        );
    }

    @Test
    void getProvider() {
        assertEquals(OAuthProvider.SPOTIFY, adapter.getProvider());
    }

    @Test
    void getAuthorizationUrl() {
        String url = adapter.getAuthorizationUrl("test-state");
        assertTrue(url.contains("https://accounts.spotify.com/authorize"));
        assertTrue(url.contains("client_id=client123"));
        assertTrue(url.contains("state=test-state"));
        assertTrue(url.contains("redirect_uri=http://callback"));
    }

    @Test
    void exchangeCode() {
        mockServer.expect(requestTo("https://accounts.spotify.com/api/token"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess(
                        """
                        {
                            "access_token": "acc_tok",
                            "token_type": "Bearer",
                            "expires_in": 3600,
                            "refresh_token": "ref_tok",
                            "scope": "scope1 scope2"
                        }
                        """, MediaType.APPLICATION_JSON));

        OAuthTokenResponse response = adapter.exchangeCode("test-code");

        assertNotNull(response);
        assertEquals("acc_tok", response.accessToken());
        assertEquals("ref_tok", response.refreshToken());
        assertEquals(3600, response.expiresIn());
        mockServer.verify();
    }

    @Test
    void refreshToken() {
        mockServer.expect(requestTo("https://accounts.spotify.com/api/token"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess(
                        """
                        {
                            "access_token": "new_acc",
                            "token_type": "Bearer",
                            "expires_in": 3600,
                            "scope": "scope1"
                        }
                        """, MediaType.APPLICATION_JSON));

        OAuthTokenResponse response = adapter.refreshToken("ref_tok");

        assertEquals("new_acc", response.accessToken());
        assertNull(response.refreshToken()); // Spotify may omit refresh token on refresh
        mockServer.verify();
    }

    @Test
    void getProviderIdentity() {
        mockServer.expect(requestTo("https://api.spotify.com/v1/me"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess(
                        """
                        {
                            "id": "spotify_user_123"
                        }
                        """, MediaType.APPLICATION_JSON));

        String identity = adapter.getProviderIdentity("tok");
        assertEquals("spotify_user_123", identity);
        mockServer.verify();
    }
    
    @Test
    void exchangeCode_Failure() {
        mockServer.expect(requestTo("https://accounts.spotify.com/api/token"))
                .andRespond(withServerError());

        assertThrows(InvalidStateException.class, () -> adapter.exchangeCode("bad"));
    }
}
