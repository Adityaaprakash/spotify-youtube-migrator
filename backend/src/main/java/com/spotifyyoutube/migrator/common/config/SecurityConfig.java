package com.spotifyyoutube.migrator.common.config;

import com.spotifyyoutube.migrator.common.logging.LoggingConstants;
import com.spotifyyoutube.migrator.common.logging.RequestCorrelationFilter;
import com.spotifyyoutube.migrator.common.security.csrf.CsrfCookieFilter;
import com.spotifyyoutube.migrator.common.security.ratelimit.ApiRateLimitingFilter;
import com.spotifyyoutube.migrator.migration.reliability.idempotency.ApiIdempotencyFilter;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.intercept.AuthorizationFilter;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;
import org.springframework.security.web.authentication.www.BasicAuthenticationFilter;
import org.springframework.security.web.context.SecurityContextHolderFilter;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler;
import org.springframework.security.web.util.matcher.AntPathRequestMatcher;
import org.springframework.security.web.util.matcher.AnyRequestMatcher;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.Arrays;
import java.util.List;

@Configuration
@EnableWebSecurity
public class SecurityConfig {

    @Value("${cors.allowed-origins:http://localhost:5173,http://localhost:3000}")
    private String allowedOrigins;

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration configuration = new CorsConfiguration();
        List<String> origins = Arrays.stream(allowedOrigins.split(","))
                .map(String::trim)
                .filter(s -> !s.isBlank())
                .toList();
        configuration.setAllowedOrigins(origins);
        configuration.setAllowedMethods(List.of("GET", "POST", "PUT", "DELETE", "OPTIONS"));
        configuration.setAllowedHeaders(List.of(
                "Content-Type",
                "X-XSRF-TOKEN",
                "Authorization",
                "Idempotency-Key",
                "Accept",
                "Origin",
                LoggingConstants.HEADER_REQUEST_ID,
                LoggingConstants.HEADER_CORRELATION_ID
        ));
        configuration.setExposedHeaders(List.of(
                "Retry-After",
                "Location",
                "Idempotency-Key",
                "Idempotency-Replayed",
                LoggingConstants.HEADER_REQUEST_ID,
                LoggingConstants.HEADER_CORRELATION_ID
        ));
        configuration.setAllowCredentials(true);
        configuration.setMaxAge(3600L);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", configuration);
        return source;
    }

    @Bean
    public SecurityFilterChain securityFilterChain(
            HttpSecurity http,
            ObjectProvider<ApiIdempotencyFilter> apiIdempotencyFilterProvider,
            ObjectProvider<ApiRateLimitingFilter> apiRateLimitingFilterProvider,
            ObjectProvider<RequestCorrelationFilter> requestCorrelationFilterProvider
    ) throws Exception {
        RequestCorrelationFilter correlationFilter = requestCorrelationFilterProvider.getIfAvailable();
        if (correlationFilter == null) {
            correlationFilter = new RequestCorrelationFilter();
        }
        http.addFilterBefore(correlationFilter, SecurityContextHolderFilter.class);

        http
            .cors(cors -> cors.configurationSource(corsConfigurationSource()))
            // Use CookieCsrfTokenRepository for SPA architecture (React). 
            // It sends XSRF-TOKEN cookie which the frontend returns in X-XSRF-TOKEN header.
            // Spring Security 6 requires explicitly setting the request handler for SPAs.
            .csrf(csrf -> csrf
                .csrfTokenRepository(CookieCsrfTokenRepository.withHttpOnlyFalse())
                .csrfTokenRequestHandler(new CsrfTokenRequestAttributeHandler())
                .ignoringRequestMatchers("/api/auth/login", "/api/auth/register", "/api/auth/logout")
            )
            .sessionManagement(session -> session
                .sessionFixation().changeSessionId()
            )
            .authorizeHttpRequests(auth -> auth
                .requestMatchers("/api/auth/login", "/api/auth/register", "/api/auth/logout").permitAll()
                // Actuator: permit public liveness/readiness health probes, info, and prometheus scraping; restrict other actuator endpoints
                .requestMatchers("/actuator/health", "/actuator/health/**", "/actuator/info", "/actuator/prometheus").permitAll()
                .requestMatchers("/actuator/**").authenticated()
                .anyRequest().authenticated()
            )
            .exceptionHandling(exceptions -> exceptions
                .defaultAuthenticationEntryPointFor(
                    new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED),
                    new AntPathRequestMatcher("/api/migrations/submit")
                )
                .defaultAuthenticationEntryPointFor(
                    new HttpStatusEntryPoint(HttpStatus.FORBIDDEN),
                    AnyRequestMatcher.INSTANCE
                )
            );

        // Add CsrfCookieFilter to populate XSRF-TOKEN cookie on safe requests
        http.addFilterAfter(new CsrfCookieFilter(), BasicAuthenticationFilter.class);

        ApiRateLimitingFilter rateLimitingFilter = apiRateLimitingFilterProvider.getIfAvailable();
        if (rateLimitingFilter != null) {
            http.addFilterBefore(rateLimitingFilter, AuthorizationFilter.class);
        }

        ApiIdempotencyFilter apiIdempotencyFilter = apiIdempotencyFilterProvider.getIfAvailable();
        if (apiIdempotencyFilter != null) {
            http.addFilterAfter(apiIdempotencyFilter, AuthorizationFilter.class);
        }

        return http.build();
    }
}
