package com.spotifyyoutube.migrator.common.security.ratelimit;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Spring configuration providing API rate limiting beans (Phase 16D).
 */
@Configuration
public class RateLimitingConfig {

    @Bean
    public ApiRateLimitingFilter apiRateLimitingFilter(
            ObjectProvider<ApiRateLimiter> rateLimiterProvider,
            ObjectProvider<RateLimitProperties> propertiesProvider,
            ObjectProvider<ObjectMapper> objectMapperProvider
    ) {
        return new ApiRateLimitingFilter(
                rateLimiterProvider.getIfAvailable(),
                propertiesProvider.getIfAvailable(),
                objectMapperProvider.getIfAvailable()
        );
    }
}
