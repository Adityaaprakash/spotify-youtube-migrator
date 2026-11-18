package com.spotifyyoutube.migrator.common.security.ratelimit;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.spotifyyoutube.migrator.common.exception.ApiError;
import com.spotifyyoutube.migrator.common.exception.ErrorCode;
import com.spotifyyoutube.migrator.common.exception.RateLimitExceededException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.Instant;

/**
 * Filter enforcing API rate limiting across sensitive and expensive endpoints (Phase 16D).
 * <p>
 * Evaluates request boundaries:
 * <ul>
 *   <li>{@code POST /api/auth/login} - brute force protection (per IP)</li>
 *   <li>{@code POST /api/auth/register} - account spam protection (per IP)</li>
 *   <li>{@code GET /api/oauth/{provider}/authorize} - OAuth flow abuse protection (per user/IP)</li>
 *   <li>Candidate search endpoints - downstream API quota protection (per user/IP)</li>
 *   <li>Migration dispatch endpoints - background worker capacity protection (per user/IP)</li>
 * </ul>
 */
public class ApiRateLimitingFilter extends OncePerRequestFilter {

    private final ApiRateLimiter rateLimiter;
    private final RateLimitProperties properties;
    private final ObjectMapper objectMapper;

    public ApiRateLimitingFilter(
            ApiRateLimiter rateLimiter,
            RateLimitProperties properties,
            ObjectMapper objectMapper
    ) {
        this.rateLimiter = rateLimiter;
        this.properties = properties != null ? properties : new RateLimitProperties();
        this.objectMapper = objectMapper != null ? objectMapper : new ObjectMapper();
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain
    ) throws ServletException, IOException {

        if (rateLimiter == null || properties == null || !properties.isEnabled()) {
            filterChain.doFilter(request, response);
            return;
        }

        String uri = request.getRequestURI();
        String method = request.getMethod();

        try {
            // 1. Authentication endpoints (IP-based)
            if ("POST".equalsIgnoreCase(method) && uri.endsWith("/api/auth/login")) {
                String key = "LOGIN:IP:" + getClientIp(request);
                rateLimiter.checkLimit(key, properties.getLogin(), "login");
            } else if ("POST".equalsIgnoreCase(method) && uri.endsWith("/api/auth/register")) {
                String key = "REGISTER:IP:" + getClientIp(request);
                rateLimiter.checkLimit(key, properties.getRegister(), "register");
            }
            // 2. OAuth authorization URL generation
            else if ("GET".equalsIgnoreCase(method) && uri.matches(".*/api/oauth/[^/]+/authorize.*")) {
                String key = deriveClientKey(request, "OAUTH");
                rateLimiter.checkLimit(key, properties.getOauth(), "oauth_authorize");
            }
            // 3. Candidate & video search endpoints
            else if (("POST".equalsIgnoreCase(method) && uri.matches(".*/api/(review-sessions|reviews)/[^/]+/tracks/[^/]+/candidates/search.*"))
                    || ("GET".equalsIgnoreCase(method) && uri.endsWith("/api/youtube/videos/search"))) {
                String key = deriveClientKey(request, "SEARCH");
                rateLimiter.checkLimit(key, properties.getSearch(), "candidate_search");
            }
            // 4. Migration dispatch & mutation endpoints (strictly state-changing POST mutations)
            else if ("POST".equalsIgnoreCase(method) && (uri.endsWith("/api/migrations/submit")
                    || uri.endsWith("/api/migrations")
                    || uri.matches(".*/api/migrations/[^/]+/resume.*")
                    || uri.matches(".*/api/migrations/execution/run.*"))) {
                String key = deriveClientKey(request, "MIGRATION");
                rateLimiter.checkLimit(key, properties.getMigration(), "migration_submit");
            }

            filterChain.doFilter(request, response);
        } catch (RateLimitExceededException ex) {
            handleRateLimitExceeded(request, response, ex);
        }
    }

    private void handleRateLimitExceeded(
            HttpServletRequest request,
            HttpServletResponse response,
            RateLimitExceededException ex
    ) throws IOException {
        response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
        response.setHeader(HttpHeaders.RETRY_AFTER, String.valueOf(ex.getRetryAfterSeconds()));
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);

        ApiError error = new ApiError(
                ErrorCode.TOO_MANY_REQUESTS,
                ex.getMessage() != null ? ex.getMessage() : "Rate limit exceeded",
                Instant.now().toString(),
                request.getRequestURI()
        );

        response.getWriter().write(objectMapper.writeValueAsString(error));
    }

    private String deriveClientKey(HttpServletRequest request, String operationPrefix) {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.isAuthenticated() && !"anonymousUser".equals(auth.getPrincipal())) {
            return operationPrefix + ":USER:" + auth.getName();
        }
        return operationPrefix + ":IP:" + getClientIp(request);
    }

    private String getClientIp(HttpServletRequest request) {
        if (properties.isTrustForwardedHeaders()) {
            String xForwardedFor = request.getHeader("X-Forwarded-For");
            if (xForwardedFor != null && !xForwardedFor.isBlank()) {
                String[] ips = xForwardedFor.split(",");
                if (ips.length > 0 && !ips[0].trim().isBlank()) {
                    return ips[0].trim();
                }
            }
        }
        String remoteAddr = request.getRemoteAddr();
        return (remoteAddr != null && !remoteAddr.isBlank()) ? remoteAddr : "unknown";
    }
}
