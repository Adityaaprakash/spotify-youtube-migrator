package com.spotifyyoutube.migrator.identity.infrastructure.provider;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.spotifyyoutube.migrator.common.exception.InvalidStateException;
import com.spotifyyoutube.migrator.identity.application.OAuthProviderAdapter;
import com.spotifyyoutube.migrator.identity.application.OAuthTokenResponse;
import com.spotifyyoutube.migrator.identity.domain.OAuthProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriComponentsBuilder;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

@Component
public class SpotifyProviderAdapter implements OAuthProviderAdapter {

    private final String clientId;
    private final String clientSecret;
    private final String redirectUri;
    private final String scopes;
    private final RestClient restClient;

    public SpotifyProviderAdapter(
            @Value("${oauth.providers.spotify.client-id}") String clientId,
            @Value("${oauth.providers.spotify.client-secret}") String clientSecret,
            @Value("${oauth.providers.spotify.redirect-uri}") String redirectUri,
            @Value("${oauth.providers.spotify.scopes}") String scopes,
            RestClient.Builder restClientBuilder) {
        this.clientId = clientId;
        this.clientSecret = clientSecret;
        this.redirectUri = redirectUri;
        this.scopes = scopes;
        this.restClient = restClientBuilder.build();
    }

    @Override
    public OAuthProvider getProvider() {
        return OAuthProvider.SPOTIFY;
    }

    @Override
    public String getAuthorizationUrl(String state) {
        return UriComponentsBuilder.fromHttpUrl("https://accounts.spotify.com/authorize")
                .queryParam("response_type", "code")
                .queryParam("client_id", clientId)
                .queryParam("scope", scopes)
                .queryParam("redirect_uri", redirectUri)
                .queryParam("state", state)
                .build()
                .toUriString();
    }

    @Override
    public OAuthTokenResponse exchangeCode(String code) {
        MultiValueMap<String, String> body = new LinkedMultiValueMap<>();
        body.add("grant_type", "authorization_code");
        body.add("code", code);
        body.add("redirect_uri", redirectUri);
        return performTokenRequest(body);
    }

    @Override
    public OAuthTokenResponse refreshToken(String refreshToken) {
        MultiValueMap<String, String> body = new LinkedMultiValueMap<>();
        body.add("grant_type", "refresh_token");
        body.add("refresh_token", refreshToken);
        return performTokenRequest(body);
    }

    private OAuthTokenResponse performTokenRequest(MultiValueMap<String, String> body) {
        String basicAuth = Base64.getEncoder().encodeToString((clientId + ":" + clientSecret).getBytes(StandardCharsets.UTF_8));

        SpotifyTokenResponse response = restClient.post()
                .uri("https://accounts.spotify.com/api/token")
                .header(HttpHeaders.AUTHORIZATION, "Basic " + basicAuth)
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .body(body)
                .retrieve()
                .onStatus(status -> status.isError(), (req, res) -> {
                    throw new InvalidStateException("Failed to exchange Spotify token: " + res.getStatusCode());
                })
                .body(SpotifyTokenResponse.class);

        if (response == null) {
            throw new InvalidStateException("Received null response from Spotify token endpoint");
        }

        return new OAuthTokenResponse(
                response.accessToken(),
                response.refreshToken(), // Might be null on refresh, handled by lifecycle service
                response.expiresIn(),
                response.scope()
        );
    }

    @Override
    public String getProviderIdentity(String accessToken) {
        SpotifyUserResponse response = restClient.get()
                .uri("https://api.spotify.com/v1/me")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)
                .retrieve()
                .onStatus(status -> status.isError(), (req, res) -> {
                    throw new InvalidStateException("Failed to fetch Spotify identity: " + res.getStatusCode());
                })
                .body(SpotifyUserResponse.class);

        if (response == null || response.id() == null) {
            throw new InvalidStateException("Invalid response when fetching Spotify identity");
        }
        return response.id();
    }

    private record SpotifyTokenResponse(
            @JsonProperty("access_token") String accessToken,
            @JsonProperty("token_type") String tokenType,
            @JsonProperty("scope") String scope,
            @JsonProperty("expires_in") int expiresIn,
            @JsonProperty("refresh_token") String refreshToken
    ) {}

    private record SpotifyUserResponse(
            @JsonProperty("id") String id
    ) {}
}
