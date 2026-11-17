package com.spotifyyoutube.migrator.identity.application;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class OAuthRedirectUriValidationTest {

    @Test
    void validateProviderRedirectUri_ValidHttps_Accepted() {
        assertDoesNotThrow(() ->
                RedirectUriValidator.validateProviderRedirectUri("https://migrator.example.com/api/oauth/spotify/callback"));
        assertDoesNotThrow(() ->
                RedirectUriValidator.validateProviderRedirectUri("https://app.domain.org:8443/api/oauth/google/callback"));
    }

    @Test
    void validateProviderRedirectUri_LocalhostHttp_Accepted() {
        assertDoesNotThrow(() ->
                RedirectUriValidator.validateProviderRedirectUri("http://localhost:8080/api/oauth/spotify/callback"));
        assertDoesNotThrow(() ->
                RedirectUriValidator.validateProviderRedirectUri("http://127.0.0.1:8080/api/oauth/google/callback"));
        assertDoesNotThrow(() ->
                RedirectUriValidator.validateProviderRedirectUri("http://[::1]:8080/api/oauth/google/callback"));
    }

    @Test
    void validateProviderRedirectUri_InsecureExternalHttp_Rejected() {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () ->
                RedirectUriValidator.validateProviderRedirectUri("http://evil.com/api/oauth/spotify/callback"));
        assertTrue(ex.getMessage().contains("HTTP scheme is only permitted for localhost"));
    }

    @Test
    void validateProviderRedirectUri_InvalidSchemes_Rejected() {
        assertThrows(IllegalArgumentException.class, () ->
                RedirectUriValidator.validateProviderRedirectUri("javascript:alert(1)"));
        assertThrows(IllegalArgumentException.class, () ->
                RedirectUriValidator.validateProviderRedirectUri("ftp://example.com/callback"));
        assertThrows(IllegalArgumentException.class, () ->
                RedirectUriValidator.validateProviderRedirectUri("file:///etc/passwd"));
    }

    @Test
    void validateProviderRedirectUri_RelativeUri_Rejected() {
        assertThrows(IllegalArgumentException.class, () ->
                RedirectUriValidator.validateProviderRedirectUri("/api/oauth/spotify/callback"));
    }

    @Test
    void validateProviderRedirectUri_FragmentPresent_Rejected() {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () ->
                RedirectUriValidator.validateProviderRedirectUri("https://example.com/callback#token=123"));
        assertTrue(ex.getMessage().contains("must not contain a fragment"));
    }

    @Test
    void validateProviderRedirectUri_NullOrBlank_Rejected() {
        assertThrows(IllegalArgumentException.class, () ->
                RedirectUriValidator.validateProviderRedirectUri(null));
        assertThrows(IllegalArgumentException.class, () ->
                RedirectUriValidator.validateProviderRedirectUri("   "));
    }

    @Test
    void validateDestinationUri_SafeRelativePaths_Allowed() {
        assertEquals("/", RedirectUriValidator.validateDestinationUri("/"));
        assertEquals("/settings", RedirectUriValidator.validateDestinationUri("/settings"));
        assertEquals("/settings", RedirectUriValidator.validateDestinationUri("   /settings   "));
        assertEquals("/migrations/123", RedirectUriValidator.validateDestinationUri("/migrations/123"));
        assertEquals("/?oauth_error=access_denied", RedirectUriValidator.validateDestinationUri("/?oauth_error=access_denied"));
    }

    @Test
    void validateDestinationUri_OpenRedirectAbsoluteUri_SanitizedToRoot() {
        assertEquals("/", RedirectUriValidator.validateDestinationUri("https://evil.com"));
        assertEquals("/", RedirectUriValidator.validateDestinationUri("http://attacker.com/malicious"));
    }

    @Test
    void validateDestinationUri_ProtocolRelativeBypass_SanitizedToRoot() {
        assertEquals("/", RedirectUriValidator.validateDestinationUri("//evil.com"));
        assertEquals("/", RedirectUriValidator.validateDestinationUri("/\\evil.com"));
        assertEquals("/", RedirectUriValidator.validateDestinationUri("\\\\evil.com"));
        assertEquals("/", RedirectUriValidator.validateDestinationUri("/path\\evil.com"));
    }

    @Test
    void validateDestinationUri_EncodedSeparators_SanitizedToRoot() {
        assertEquals("/", RedirectUriValidator.validateDestinationUri("/%2fevil.com"));
        assertEquals("/", RedirectUriValidator.validateDestinationUri("/%2Fevil.com"));
        assertEquals("/", RedirectUriValidator.validateDestinationUri("/%5cevil.com"));
        assertEquals("/", RedirectUriValidator.validateDestinationUri("/%5Cevil.com"));
    }

    @Test
    void validateDestinationUri_PathTraversal_SanitizedToRoot() {
        assertEquals("/", RedirectUriValidator.validateDestinationUri("/../evil"));
        assertEquals("/", RedirectUriValidator.validateDestinationUri("/settings/../evil"));
    }

    @Test
    void validateDestinationUri_JavascriptOrDataScheme_SanitizedToRoot() {
        assertEquals("/", RedirectUriValidator.validateDestinationUri("javascript:alert(1)"));
        assertEquals("/", RedirectUriValidator.validateDestinationUri("data:text/html,malicious"));
    }

    @Test
    void validateDestinationUri_CrlfInjection_SanitizedToRoot() {
        assertEquals("/", RedirectUriValidator.validateDestinationUri("/path\r\nSet-Cookie: evil=1"));
        assertEquals("/", RedirectUriValidator.validateDestinationUri("/path\nInjected: value"));
    }

    @Test
    void validateDestinationUri_NullOrBlank_DefaultsToRoot() {
        assertEquals("/", RedirectUriValidator.validateDestinationUri(null));
        assertEquals("/", RedirectUriValidator.validateDestinationUri(""));
        assertEquals("/", RedirectUriValidator.validateDestinationUri("   "));
    }
}
