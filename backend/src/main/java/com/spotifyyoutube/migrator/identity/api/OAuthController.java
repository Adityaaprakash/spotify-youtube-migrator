package com.spotifyyoutube.migrator.identity.api;

import com.spotifyyoutube.migrator.common.exception.ResourceNotFoundException;
import com.spotifyyoutube.migrator.common.security.logging.SensitiveDataSanitizer;
import com.spotifyyoutube.migrator.identity.api.dto.OAuthConnectionStatusDto;
import com.spotifyyoutube.migrator.identity.application.OAuthAuthorizationService;
import com.spotifyyoutube.migrator.identity.application.OAuthStateService;
import com.spotifyyoutube.migrator.identity.application.OAuthTokenLifecycleService;
import com.spotifyyoutube.migrator.identity.application.RedirectUriValidator;
import com.spotifyyoutube.migrator.identity.domain.OAuthConnection;
import com.spotifyyoutube.migrator.identity.domain.OAuthProvider;
import com.spotifyyoutube.migrator.identity.domain.User;
import com.spotifyyoutube.migrator.identity.repository.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Map;

@RestController
@RequestMapping("/api/oauth")
public class OAuthController {

    private static final Logger log = LoggerFactory.getLogger(OAuthController.class);

    private final OAuthAuthorizationService authorizationService;
    private final OAuthStateService stateService;
    private final OAuthTokenLifecycleService tokenLifecycleService;
    private final UserRepository userRepository;

    public OAuthController(
            OAuthAuthorizationService authorizationService,
            OAuthStateService stateService,
            OAuthTokenLifecycleService tokenLifecycleService,
            UserRepository userRepository) {
        this.authorizationService = authorizationService;
        this.stateService = stateService;
        this.tokenLifecycleService = tokenLifecycleService;
        this.userRepository = userRepository;
    }

    @GetMapping("/{provider}/authorize")
    public ResponseEntity<Map<String, String>> generateAuthUrl(@PathVariable("provider") String providerStr) {
        User user = getCurrentUser();
        OAuthProvider provider = parseProvider(providerStr);
        String url = authorizationService.generateAuthorizationUrl(user.getId(), provider);
        
        return ResponseEntity.ok(Map.of("url", url));
    }

    @GetMapping("/{provider}/callback")
    public ResponseEntity<Void> handleCallback(
            @PathVariable("provider") String providerStr,
            @RequestParam(value = "code", required = false) String code,
            @RequestParam(value = "state", required = false) String state,
            @RequestParam(value = "error", required = false) String error,
            @RequestParam(value = "error_description", required = false) String errorDescription) {
        
        OAuthProvider provider = parseProvider(providerStr);

        // Handle provider denial or error response (RFC 6749 Section 4.1.2.1)
        if (error != null && !error.isBlank()) {
            log.warn("OAuth provider {} returned error during authorization: {}", provider, SensitiveDataSanitizer.sanitizeSingleLine(error));
            if (state != null && !state.isBlank()) {
                stateService.invalidateState(state);
            }
            String sanitizedError = URLEncoder.encode(error, StandardCharsets.UTF_8);
            String redirectUrl = RedirectUriValidator.validateDestinationUri("/?oauth_error=" + sanitizedError);
            return ResponseEntity.status(HttpStatus.FOUND).location(URI.create(redirectUrl)).build();
        }

        // Validate presence of authorization code
        if (code == null || code.isBlank()) {
            if (state != null && !state.isBlank()) {
                stateService.invalidateState(state);
            }
            String redirectUrl = RedirectUriValidator.validateDestinationUri("/?oauth_error=missing_code");
            return ResponseEntity.status(HttpStatus.FOUND).location(URI.create(redirectUrl)).build();
        }

        // Validate presence of state parameter
        if (state == null || state.isBlank()) {
            String redirectUrl = RedirectUriValidator.validateDestinationUri("/?oauth_error=missing_state");
            return ResponseEntity.status(HttpStatus.FOUND).location(URI.create(redirectUrl)).build();
        }

        User user = getCurrentUser();
        authorizationService.handleCallback(code, state, user.getId(), provider);
        
        String safeDestination = RedirectUriValidator.validateDestinationUri("/");
        return ResponseEntity.status(HttpStatus.FOUND).location(URI.create(safeDestination)).build();
    }

    @GetMapping("/{provider}")
    public ResponseEntity<OAuthConnectionStatusDto> getConnectionStatus(@PathVariable("provider") String providerStr) {
        User user = getCurrentUser();
        OAuthProvider provider = parseProvider(providerStr);
        
        OAuthConnection connection = tokenLifecycleService.getConnectionStatus(user.getId(), provider);
        if (connection == null) {
            return ResponseEntity.ok(new OAuthConnectionStatusDto(provider.name(), false, null, null, "DISCONNECTED"));
        }
        
        OAuthConnectionStatusDto dto = new OAuthConnectionStatusDto(
                connection.getProvider().name(),
                true,
                connection.getProviderUserId(),
                connection.getExpiresAt(),
                connection.getStatus() != null ? connection.getStatus().name() : "CONNECTED"
        );
        return ResponseEntity.ok(dto);
    }

    @DeleteMapping("/{provider}")
    public ResponseEntity<Void> disconnectConnection(@PathVariable("provider") String providerStr) {
        User user = getCurrentUser();
        OAuthProvider provider = parseProvider(providerStr);
        
        tokenLifecycleService.disconnect(user.getId(), provider);
        return ResponseEntity.noContent().build();
    }

    private User getCurrentUser() {
        if (SecurityContextHolder.getContext().getAuthentication() == null ||
            !SecurityContextHolder.getContext().getAuthentication().isAuthenticated() ||
            "anonymousUser".equals(SecurityContextHolder.getContext().getAuthentication().getPrincipal())) {
            throw new ResourceNotFoundException("User not authenticated.");
        }
        String email = SecurityContextHolder.getContext().getAuthentication().getName();
        return userRepository.findByEmail(email)
                .orElseThrow(() -> new ResourceNotFoundException("User not found"));
    }

    private OAuthProvider parseProvider(String providerStr) {
        try {
            return OAuthProvider.valueOf(providerStr.toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Invalid OAuth provider: " + providerStr);
        }
    }
}
