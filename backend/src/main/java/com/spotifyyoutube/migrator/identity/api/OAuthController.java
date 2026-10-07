package com.spotifyyoutube.migrator.identity.api;

import com.spotifyyoutube.migrator.identity.application.OAuthAuthorizationService;
import com.spotifyyoutube.migrator.identity.domain.OAuthProvider;
import com.spotifyyoutube.migrator.identity.domain.User;
import com.spotifyyoutube.migrator.identity.repository.UserRepository;
import com.spotifyyoutube.migrator.common.exception.ResourceNotFoundException;
import com.spotifyyoutube.migrator.identity.api.dto.OAuthConnectionStatusDto;
import com.spotifyyoutube.migrator.identity.application.OAuthTokenLifecycleService;
import com.spotifyyoutube.migrator.identity.domain.OAuthConnection;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.util.Map;

@RestController
@RequestMapping("/api/oauth")
public class OAuthController {

    private final OAuthAuthorizationService authorizationService;
    private final OAuthTokenLifecycleService tokenLifecycleService;
    private final UserRepository userRepository;

    public OAuthController(
            OAuthAuthorizationService authorizationService,
            OAuthTokenLifecycleService tokenLifecycleService,
            UserRepository userRepository) {
        this.authorizationService = authorizationService;
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
            @RequestParam("code") String code,
            @RequestParam("state") String state) {
        
        User user = getCurrentUser();
        OAuthProvider provider = parseProvider(providerStr);
        
        authorizationService.handleCallback(code, state, user.getId(), provider);
        
        // Redirect to a frontend success page or close the popup
        // In a real app this would map to a configuration property, but returning 302 to root is a reasonable default.
        return ResponseEntity.status(HttpStatus.FOUND).location(URI.create("/")).build();
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
