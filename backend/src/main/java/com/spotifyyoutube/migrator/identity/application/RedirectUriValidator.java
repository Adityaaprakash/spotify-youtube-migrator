package com.spotifyyoutube.migrator.identity.application;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.regex.Pattern;

public final class RedirectUriValidator {

    private static final Pattern SAFE_RELATIVE_PATH_PATTERN = Pattern.compile("^/[a-zA-Z0-9_\\-./?=&%#]*$");

    private RedirectUriValidator() {
        // Utility class
    }

    public static void validateProviderRedirectUri(String redirectUri) {
        if (redirectUri == null || redirectUri.isBlank()) {
            throw new IllegalArgumentException("Provider redirect URI must not be null or blank");
        }
        try {
            URI uri = new URI(redirectUri);
            if (!uri.isAbsolute()) {
                throw new IllegalArgumentException("Provider redirect URI must be absolute: " + redirectUri);
            }
            String scheme = uri.getScheme();
            if (scheme == null || (!"http".equalsIgnoreCase(scheme) && !"https".equalsIgnoreCase(scheme))) {
                throw new IllegalArgumentException("Provider redirect URI must use HTTP or HTTPS scheme: " + redirectUri);
            }
            String host = uri.getHost();
            if (host == null || host.isBlank()) {
                throw new IllegalArgumentException("Provider redirect URI must have a valid host: " + redirectUri);
            }
            if ("http".equalsIgnoreCase(scheme) && !isLocalHost(host)) {
                throw new IllegalArgumentException("HTTP scheme is only permitted for localhost or internal hosts: " + redirectUri);
            }
            if (uri.getFragment() != null) {
                throw new IllegalArgumentException("Provider redirect URI must not contain a fragment: " + redirectUri);
            }
        } catch (URISyntaxException e) {
            throw new IllegalArgumentException("Provider redirect URI has invalid syntax: " + redirectUri, e);
        }
    }

    public static String validateDestinationUri(String destination) {
        if (destination == null || destination.isBlank()) {
            return "/";
        }
        String trimmed = destination.trim();

        // Reject CRLF injection
        if (trimmed.contains("\r") || trimmed.contains("\n")) {
            return "/";
        }
        // Reject backslashes anywhere in destination (e.g. /\evil.com, \\evil.com, /path\evil)
        if (trimmed.contains("\\")) {
            return "/";
        }
        // Reject protocol-relative bypass (e.g. //evil.com)
        if (trimmed.startsWith("//")) {
            return "/";
        }
        // Reject URL-encoded slashes or backslashes (%2f, %5c)
        String lower = trimmed.toLowerCase();
        if (lower.contains("%2f") || lower.contains("%5c")) {
            return "/";
        }
        // Reject path traversal attempts
        if (trimmed.contains("/..") || trimmed.contains("../")) {
            return "/";
        }
        // Reject protocol schemes (e.g. javascript:, data:, https:) where colon appears before query string
        int colonIdx = trimmed.indexOf(':');
        int queryIdx = trimmed.indexOf('?');
        if (colonIdx != -1 && (queryIdx == -1 || colonIdx < queryIdx)) {
            return "/";
        }
        // Must start with single slash
        if (!trimmed.startsWith("/")) {
            return "/";
        }
        // Check allowed character pattern
        if (!SAFE_RELATIVE_PATH_PATTERN.matcher(trimmed).matches()) {
            return "/";
        }
        return trimmed;
    }

    private static boolean isLocalHost(String host) {
        return "localhost".equalsIgnoreCase(host)
                || "127.0.0.1".equals(host)
                || "::1".equals(host)
                || "[::1]".equals(host)
                || !host.contains(".");
    }
}
