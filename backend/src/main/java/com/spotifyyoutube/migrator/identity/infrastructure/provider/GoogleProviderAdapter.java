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

@Component
public class GoogleProviderAdapter implements OAuthProviderAdapter {

    private final String clientId;
    private final String clientSecret;
    private final String redirectUri;
    private final String scopes;
    private final RestClient restClient;

    public GoogleProviderAdapter(
            @Value("${oauth.providers.google.client-id}") String clientId,
            @Value("${oauth.providers.google.client-secret}") String clientSecret,
            @Value("${oauth.providers.google.redirect-uri}") String redirectUri,
            @Value("${oauth.providers.google.scopes}") String scopes,
            RestClient.Builder restClientBuilder) {
        this.clientId = clientId;
        this.clientSecret = clientSecret;
        this.redirectUri = redirectUri;
        this.scopes = scopes;
        this.restClient = restClientBuilder.build();
    }

    @Override
    public OAuthProvider getProvider() {
        return OAuthProvider.GOOGLE;
    }

    @Override
    public String getAuthorizationUrl(String state) {
        return UriComponentsBuilder.fromHttpUrl("https://accounts.google.com/o/oauth2/v2/auth")
                .queryParam("response_type", "code")
                .queryParam("client_id", clientId)
                .queryParam("scope", scopes)
                .queryParam("redirect_uri", redirectUri)
                .queryParam("state", state)
                .queryParam("access_type", "offline")
                .queryParam("prompt", "consent")
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
        body.add("client_id", clientId);
        body.add("client_secret", clientSecret);

        GoogleTokenResponse response = restClient.post()
                .uri("https://oauth2.googleapis.com/token")
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .body(body)
                .retrieve()
                .onStatus(status -> status.isError(), (req, res) -> {
                    throw new InvalidStateException("Failed to exchange Google token: " + res.getStatusCode() + " body: " + new String(res.getBody().readAllBytes()));
                })
                .body(GoogleTokenResponse.class);

        if (response == null) {
            throw new InvalidStateException("Received null response from Google token endpoint");
        }

        return new OAuthTokenResponse(
                response.accessToken(),
                response.refreshToken(), // Might be null on refresh, lifecycle service persists previous one
                response.expiresIn(),
                response.scope()
        );
    }

    @Override
    public String getProviderIdentity(String accessToken) {
        GoogleUserResponse response = restClient.get()
                .uri("https://www.googleapis.com/oauth2/v3/userinfo")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)
                .retrieve()
                .onStatus(status -> status.isError(), (req, res) -> {
                    throw new InvalidStateException("Failed to fetch Google identity: " + res.getStatusCode());
                })
                .body(GoogleUserResponse.class);

        if (response == null || response.sub() == null) {
            throw new InvalidStateException("Invalid response when fetching Google identity");
        }
        return response.sub();
    }

    private record GoogleTokenResponse(
            @JsonProperty("access_token") String accessToken,
            @JsonProperty("expires_in") int expiresIn,
            @JsonProperty("refresh_token") String refreshToken,
            @JsonProperty("scope") String scope,
            @JsonProperty("token_type") String tokenType,
            @JsonProperty("id_token") String idToken
    ) {}

    private record GoogleUserResponse(
            @JsonProperty("sub") String sub,
            @JsonProperty("email") String email
    ) {}
}
