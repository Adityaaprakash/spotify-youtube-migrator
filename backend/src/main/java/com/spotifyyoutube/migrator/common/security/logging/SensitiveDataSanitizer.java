package com.spotifyyoutube.migrator.common.security.logging;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Centralized sensitive data sanitizer utility for logs, diagnostics, and exceptions (Phase 16F).
 * <p>
 * Ensures zero leakage of credentials, access tokens, refresh tokens, client secrets,
 * passwords, API keys, OAuth state tokens, session cookies, and authorization headers.
 * <p>
 * Thread-safe, stateless, idempotent, and deterministic.
 */
public final class SensitiveDataSanitizer {

    public static final String DEFAULT_REDACTED = "[REDACTED]";

    // Pattern for Bearer tokens: e.g. Bearer ya29...
    private static final Pattern BEARER_PATTERN = Pattern.compile(
            "(?i)\\b(Bearer\\s+)(?!\\[REDACTED\\]|\\*\\*\\*)[A-Za-z0-9._~+/=-]+",
            Pattern.CASE_INSENSITIVE
    );

    // Pattern for Basic auth credentials: e.g. Basic dXNlcjpwYXNz
    private static final Pattern BASIC_PATTERN = Pattern.compile(
            "(?i)\\b(Basic\\s+)(?!\\[REDACTED\\]|\\*\\*\\*)[A-Za-z0-9+/=]+",
            Pattern.CASE_INSENSITIVE
    );

    // Pattern for Authorization headers: e.g. Authorization: Bearer xyz or Authorization: Basic xyz or Authorization: xyz
    private static final Pattern AUTH_HEADER_PATTERN = Pattern.compile(
            "(?i)\\b(Authorization\\s*:\\s*(?:Bearer\\s+|Basic\\s+)?)(?!\\[REDACTED\\]|\\*\\*\\*)[^\\s\"';,]+",
            Pattern.CASE_INSENSITIVE
    );

    // Pattern for key-value / query parameter / form body credentials:
    // e.g. access_token=xyz, refresh_token=xyz, client_secret=xyz, password=xyz, code=xyz, state=xyz, api_key=xyz
    private static final Pattern KEY_VALUE_PATTERN = Pattern.compile(
            "(?i)\\b(access_token|refresh_token|id_token|token|client_secret|secret|password|api_key|apikey|key|code|state|jsessionid|xsrf)=(?!\\[REDACTED\\]|\\*\\*\\*)[^&\\s\"';,]+",
            Pattern.CASE_INSENSITIVE
    );

    // Pattern for JSON string key-value pairs:
    // e.g. "access_token": "ya29...", "client_secret": "secret", "password": "pass"
    private static final Pattern JSON_STRING_PATTERN = Pattern.compile(
            "(?i)(\"(?:access_token|refresh_token|id_token|token|client_secret|secret|password|api_key|apikey|code|state|jsessionid|xsrf_token|csrf_token)\"\\s*:\\s*)\"(?!\\[REDACTED\\]|\\*\\*\\*)(?:[^\"\\\\]|\\\\.)*\"",
            Pattern.CASE_INSENSITIVE
    );

    // Pattern for Cookie headers:
    // e.g. JSESSIONID=abc, XSRF-TOKEN=xyz, SESSION=123
    private static final Pattern COOKIE_PATTERN = Pattern.compile(
            "(?i)\\b(JSESSIONID|SESSION|XSRF-TOKEN|CSRF-TOKEN)=(?!\\[REDACTED\\]|\\*\\*\\*)[^;\\s\"']+",
            Pattern.CASE_INSENSITIVE
    );

    // Pattern for CRLF log injection neutralization
    private static final Pattern CRLF_PATTERN = Pattern.compile("[\\r\\n]+");

    private static final int MAX_CAUSE_DEPTH = 20;

    private SensitiveDataSanitizer() {
        // utility class
    }

    /**
     * Sanitizes sensitive credentials, tokens, secrets, cookies, and headers from the given text
     * using the default {@value #DEFAULT_REDACTED} replacement.
     *
     * @param text raw input text
     * @return sanitized text, or {@code null} if input is null
     */
    public static String sanitize(String text) {
        return sanitize(text, DEFAULT_REDACTED);
    }

    /**
     * Sanitizes sensitive credentials using a custom replacement string.
     *
     * @param text raw input text
     * @param replacement replacement string (e.g. "[REDACTED]" or "***")
     * @return sanitized text, or {@code null} if input is null
     */
    public static String sanitize(String text, String replacement) {
        if (text == null) {
            return null;
        }
        if (text.isBlank()) {
            return text;
        }
        String rep = (replacement != null) ? replacement : DEFAULT_REDACTED;

        String sanitized = AUTH_HEADER_PATTERN.matcher(text).replaceAll("$1" + rep);
        sanitized = BEARER_PATTERN.matcher(sanitized).replaceAll("$1" + rep);
        sanitized = BASIC_PATTERN.matcher(sanitized).replaceAll("$1" + rep);
        sanitized = KEY_VALUE_PATTERN.matcher(sanitized).replaceAll("$1=" + rep);
        sanitized = JSON_STRING_PATTERN.matcher(sanitized).replaceAll("$1\"" + rep + "\"");
        sanitized = COOKIE_PATTERN.matcher(sanitized).replaceAll("$1=" + rep);
        return sanitized;
    }

    /**
     * Sanitizes sensitive data and neutralizes CRLF / newline characters to prevent
     * log injection and log splitting attacks.
     *
     * @param text raw input text
     * @return sanitized single-line text, or {@code null} if input is null
     */
    public static String sanitizeSingleLine(String text) {
        if (text == null) {
            return null;
        }
        String sanitized = sanitize(text);
        return CRLF_PATTERN.matcher(sanitized).replaceAll(" ").trim();
    }

    /**
     * Traverses the cause chain of a {@link Throwable} up to {@value #MAX_CAUSE_DEPTH} levels,
     * sanitizing all exception messages to prevent sensitive data leakage in stack traces and logs.
     * Guards against circular reference cycles using identity tracking.
     *
     * @param throwable throwable to sanitize
     * @return sanitized diagnostic description, or {@code null} if throwable is null
     */
    public static String sanitize(Throwable throwable) {
        if (throwable == null) {
            return null;
        }
        StringBuilder sb = new StringBuilder();
        Set<Throwable> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        Throwable current = throwable;
        int depth = 0;

        while (current != null && depth < MAX_CAUSE_DEPTH && seen.add(current)) {
            if (depth > 0) {
                sb.append("; caused by: ");
            }
            sb.append(current.getClass().getName());
            String msg = current.getMessage();
            if (msg != null && !msg.isBlank()) {
                sb.append(": ").append(sanitize(msg));
            }
            current = current.getCause();
            depth++;
        }
        return sb.toString();
    }

    /**
     * Extracts and sanitizes only the direct message of a {@link Throwable},
     * falling back to the simple class name if the message is absent or blank.
     *
     * @param throwable throwable to extract message from
     * @return sanitized message or simple class name
     */
    public static String sanitizeThrowableMessage(Throwable throwable) {
        if (throwable == null) {
            return "null";
        }
        String msg = throwable.getMessage();
        if (msg == null || msg.isBlank()) {
            return throwable.getClass().getSimpleName();
        }
        return sanitize(msg);
    }
}
