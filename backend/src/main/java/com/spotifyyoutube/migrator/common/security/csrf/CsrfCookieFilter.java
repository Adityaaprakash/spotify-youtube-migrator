package com.spotifyyoutube.migrator.common.security.csrf;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Filter that triggers deferred CSRF token resolution for SPA clients (Phase 16D).
 * <p>
 * In Spring Security 6, {@link org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler}
 * defers token loading until requested. By invoking {@link CsrfToken#getToken()}, this filter forces
 * the token repository to render the {@code XSRF-TOKEN} cookie on safe requests (e.g. initial SPA page load,
 * {@code GET /api/auth/me}), allowing subsequent mutating SPA requests to supply {@code X-XSRF-TOKEN}.
 */
public class CsrfCookieFilter extends OncePerRequestFilter {

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain
    ) throws ServletException, IOException {
        CsrfToken csrfToken = (CsrfToken) request.getAttribute(CsrfToken.class.getName());
        if (csrfToken != null) {
            csrfToken.getToken(); // Forces token resolution and cookie population
        }
        filterChain.doFilter(request, response);
    }
}
