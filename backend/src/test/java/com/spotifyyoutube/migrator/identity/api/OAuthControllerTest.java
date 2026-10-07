package com.spotifyyoutube.migrator.identity.api;

import com.spotifyyoutube.migrator.identity.application.OAuthAuthorizationService;
import com.spotifyyoutube.migrator.identity.application.OAuthTokenLifecycleService;
import com.spotifyyoutube.migrator.identity.domain.ConnectionStatus;
import com.spotifyyoutube.migrator.identity.domain.OAuthConnection;
import com.spotifyyoutube.migrator.identity.domain.OAuthProvider;
import com.spotifyyoutube.migrator.identity.domain.User;
import com.spotifyyoutube.migrator.identity.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Optional;
import java.util.UUID;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(OAuthController.class)
@AutoConfigureMockMvc(addFilters = false) // Bypass standard security filters for isolated controller tests, we mock User fetching
class OAuthControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private OAuthAuthorizationService authorizationService;

    @MockBean
    private OAuthTokenLifecycleService tokenLifecycleService;

    @MockBean
    private UserRepository userRepository;

    private User testUser;
    private final UUID userId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        testUser = new User();
        testUser.setId(userId);
        testUser.setEmail("test@example.com");
    }

    @Test
    @WithMockUser("test@example.com")
    void generateAuthUrl_ReturnsUrl() throws Exception {
        when(userRepository.findByEmail("test@example.com")).thenReturn(Optional.of(testUser));
        when(authorizationService.generateAuthorizationUrl(userId, OAuthProvider.SPOTIFY))
                .thenReturn("http://auth-url");

        mockMvc.perform(get("/api/oauth/spotify/authorize"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.url").value("http://auth-url"));
    }

    @Test
    @WithMockUser("test@example.com")
    void handleCallback_Redirects() throws Exception {
        when(userRepository.findByEmail("test@example.com")).thenReturn(Optional.of(testUser));

        mockMvc.perform(get("/api/oauth/google/callback")
                        .param("code", "valid-code")
                        .param("state", "valid-state"))
                .andExpect(status().isFound())
                .andExpect(header().string("Location", "/"));

        verify(authorizationService).handleCallback("valid-code", "valid-state", userId, OAuthProvider.GOOGLE);
    }

    @Test
    @WithMockUser("test@example.com")
    void getConnectionStatus_ReturnsConnected() throws Exception {
        when(userRepository.findByEmail("test@example.com")).thenReturn(Optional.of(testUser));
        
        OAuthConnection connection = new OAuthConnection();
        connection.setProvider(OAuthProvider.SPOTIFY);
        connection.setStatus(ConnectionStatus.CONNECTED);
        
        when(tokenLifecycleService.getConnectionStatus(userId, OAuthProvider.SPOTIFY)).thenReturn(connection);

        mockMvc.perform(get("/api/oauth/spotify"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.provider").value("SPOTIFY"))
                .andExpect(jsonPath("$.connected").value(true))
                .andExpect(jsonPath("$.status").value("CONNECTED"));
    }

    @Test
    @WithMockUser("test@example.com")
    void disconnect_CallsService() throws Exception {
        when(userRepository.findByEmail("test@example.com")).thenReturn(Optional.of(testUser));

        mockMvc.perform(delete("/api/oauth/google"))
                .andExpect(status().isNoContent());

        verify(tokenLifecycleService).disconnect(userId, OAuthProvider.GOOGLE);
    }
}
