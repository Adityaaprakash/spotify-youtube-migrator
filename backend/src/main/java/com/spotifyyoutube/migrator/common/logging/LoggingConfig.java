package com.spotifyyoutube.migrator.common.logging;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Spring configuration providing logging and correlation beans (Phase 18F).
 */
@Configuration
public class LoggingConfig {

    @Bean
    public RequestCorrelationFilter requestCorrelationFilter(
            @Value("${spring.application.name:migrator}") String applicationName,
            @Value("${APP_ENV:${spring.profiles.active:local}}") String environment
    ) {
        return new RequestCorrelationFilter(applicationName, environment);
    }

    @Bean
    public FilterRegistrationBean<RequestCorrelationFilter> requestCorrelationFilterRegistration(
            RequestCorrelationFilter filter
    ) {
        FilterRegistrationBean<RequestCorrelationFilter> registration = new FilterRegistrationBean<>(filter);
        // Managed inside Spring Security SecurityFilterChain; disable duplicate standalone servlet registration
        registration.setEnabled(false);
        return registration;
    }
}
