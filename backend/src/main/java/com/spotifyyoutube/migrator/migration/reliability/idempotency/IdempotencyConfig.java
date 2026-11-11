package com.spotifyyoutube.migrator.migration.reliability.idempotency;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.spotifyyoutube.migrator.identity.application.UserService;
import com.spotifyyoutube.migrator.migration.review.repository.ReviewSessionRepository;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.Optional;

/**
 * Spring configuration for Phase 13E HTTP API Idempotency filter.
 * <p>
 * Exposes {@link ApiIdempotencyFilter} as a managed Spring bean wired into
 * the Spring Security {@code SecurityFilterChain} after {@code AuthorizationFilter}.
 * Standalone servlet registration is disabled to prevent duplicate filter execution.
 */
@Configuration
public class IdempotencyConfig {

    @Bean
    public ApiIdempotencyFilter apiIdempotencyFilter(
            MigrationIdempotencyService idempotencyService,
            CanonicalRequestFingerprinter fingerprinter,
            ReviewSessionRepository reviewSessionRepository,
            UserService userService,
            ObjectMapper objectMapper,
            Optional<IdempotencyClaimHeartbeatService> claimHeartbeatService
    ) {
        return new ApiIdempotencyFilter(
                idempotencyService,
                fingerprinter,
                reviewSessionRepository,
                userService,
                objectMapper,
                claimHeartbeatService.orElse(null)
        );
    }

    @Bean
    public FilterRegistrationBean<ApiIdempotencyFilter> idempotencyFilterRegistration(
            ApiIdempotencyFilter filter
    ) {
        FilterRegistrationBean<ApiIdempotencyFilter> registration = new FilterRegistrationBean<>(filter);
        // Managed inside Spring Security SecurityFilterChain; disable duplicate servlet container registration
        registration.setEnabled(false);
        return registration;
    }
}
