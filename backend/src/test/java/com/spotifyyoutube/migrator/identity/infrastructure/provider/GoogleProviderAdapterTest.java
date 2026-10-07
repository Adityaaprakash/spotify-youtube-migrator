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

class GoogleProviderAdapterTest {

    private GoogleProviderAdapter adapter;
    private MockRestServiceServer mockServer;

    @BeforeEach
    void setUp() {
        RestTemplate restTemplate = new RestTemplate();
        mockServer = MockRestServiceServer.createServer(restTemplate);

        RestClient.Builder restClientBuilder = RestClient.builder()
                .messageConverters(converters -> converters.addAll(restTemplate.getMessageConverters()))
                .requestFactory(restTemplate.getRequestFactory());

        adapter = new GoogleProviderAdapter(
                "google123",
                "gsec456",
                "http://gcallback",
                "openid email",
                restClientBuilder
        );
    }

    @Test
    void getProvider() {
        assertEquals(OAuthProvider.GOOGLE, adapter.getProvider());
    }

    @Test
    void getAuthorizationUrl() {
        String url = adapter.getAuthorizationUrl("test-state");
        assertTrue(url.contains("https://accounts.google.com/o/oauth2/v2/auth"));
        assertTrue(url.contains("client_id=google123"));
        assertTrue(url.contains("access_type=offline"));
        assertTrue(url.contains("prompt=consent"));
    }

    @Test
    void exchangeCode() {
        mockServer.expect(requestTo("https://oauth2.googleapis.com/token"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess(
                        """
                        {
                            "access_token": "g_acc",
                            "expires_in": 3600,
                            "refresh_token": "g_ref",
                            "scope": "openid"
                        }
                        """, MediaType.APPLICATION_JSON));

        OAuthTokenResponse response = adapter.exchangeCode("test-code");

        assertEquals("g_acc", response.accessToken());
        assertEquals("g_ref", response.refreshToken());
        mockServer.verify();
    }

    @Test
    void getProviderIdentity() {
        mockServer.expect(requestTo("https://www.googleapis.com/oauth2/v3/userinfo"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess(
                        """
                        {
                            "sub": "google_user_sub"
                        }
                        """, MediaType.APPLICATION_JSON));

        String sub = adapter.getProviderIdentity("g_acc");
        assertEquals("google_user_sub", sub);
        mockServer.verify();
    }

    @Test
    void refreshToken_Failure() {
        mockServer.expect(requestTo("https://oauth2.googleapis.com/token"))
                .andRespond(withBadRequest().body("invalid_grant"));

        assertThrows(InvalidStateException.class, () -> adapter.refreshToken("bad_ref"));
    }
}
