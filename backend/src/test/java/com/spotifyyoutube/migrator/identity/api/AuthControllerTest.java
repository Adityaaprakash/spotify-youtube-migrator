package com.spotifyyoutube.migrator.identity.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.spotifyyoutube.migrator.common.config.SecurityConfig;
import com.spotifyyoutube.migrator.common.exception.ConflictException;
import com.spotifyyoutube.migrator.common.exception.GlobalExceptionHandler;
import com.spotifyyoutube.migrator.common.exception.ResourceNotFoundException;
import com.spotifyyoutube.migrator.identity.api.dto.LoginRequest;
import com.spotifyyoutube.migrator.identity.api.dto.RegisterRequest;
import com.spotifyyoutube.migrator.identity.application.AuthenticationService;
import com.spotifyyoutube.migrator.identity.domain.User;
import com.spotifyyoutube.migrator.identity.domain.UserStatus;
import com.spotifyyoutube.migrator.identity.repository.UserRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.test.context.support.WithAnonymousUser;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Optional;
import java.util.UUID;

import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.cookie;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(AuthController.class)
@Import({SecurityConfig.class, GlobalExceptionHandler.class})
@DisplayName("AuthController WebMvc Tests")
class AuthControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockBean
    private AuthenticationService authenticationService;

    @MockBean
    private UserRepository userRepository;

    @Test
    @DisplayName("register: valid request returns 201 Created with UserResponse")
    void register_validRequest_returns201Created() throws Exception {
        UUID userId = UUID.randomUUID();
        User user = new User("alice@example.com");
        user.setId(userId);
        user.setDisplayName("Alice");
        user.setStatus(UserStatus.ACTIVE);

        when(authenticationService.register("alice@example.com", "securePassword123", "Alice"))
                .thenReturn(user);

        RegisterRequest request = new RegisterRequest("alice@example.com", "securePassword123", "Alice");

        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(userId.toString()))
                .andExpect(jsonPath("$.email").value("alice@example.com"))
                .andExpect(jsonPath("$.displayName").value("Alice"))
                .andExpect(jsonPath("$.status").value("ACTIVE"));

        verify(authenticationService).register("alice@example.com", "securePassword123", "Alice");
    }

    @Test
    @DisplayName("register: missing body returns 400 Bad Request")
    void register_missingBody_returns400BadRequest() throws Exception {
        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
    }

    @Test
    @DisplayName("register: invalid email format returns 400 Bad Request")
    void register_invalidEmail_returns400BadRequest() throws Exception {
        RegisterRequest request = new RegisterRequest("not-an-email", "securePassword123", "Alice");

        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
    }

    @Test
    @DisplayName("register: short password returns 400 Bad Request")
    void register_shortPassword_returns400BadRequest() throws Exception {
        RegisterRequest request = new RegisterRequest("alice@example.com", "12345", "Alice");

        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
    }

    @Test
    @DisplayName("register: blank display name returns 400 Bad Request")
    void register_blankDisplayName_returns400BadRequest() throws Exception {
        RegisterRequest request = new RegisterRequest("alice@example.com", "securePassword123", "   ");

        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
    }

    @Test
    @DisplayName("register: duplicate email throws ConflictException and returns 409 Conflict")
    void register_duplicateEmail_returns409Conflict() throws Exception {
        when(authenticationService.register(eq("alice@example.com"), any(), any()))
                .thenThrow(new ConflictException("Email already in use: alice@example.com"));

        RegisterRequest request = new RegisterRequest("alice@example.com", "securePassword123", "Alice");

        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CONFLICT"))
                .andExpect(jsonPath("$.message").value("Email already in use: alice@example.com"));
    }

    @Test
    @DisplayName("login: valid credentials return 200 OK with UserResponse")
    void login_validCredentials_returns200Ok() throws Exception {
        UUID userId = UUID.randomUUID();
        User user = new User("bob@example.com");
        user.setId(userId);
        user.setDisplayName("Bob");
        user.setStatus(UserStatus.ACTIVE);

        when(authenticationService.login("bob@example.com", "correctPassword123"))
                .thenReturn(user);

        LoginRequest request = new LoginRequest("bob@example.com", "correctPassword123");

        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(userId.toString()))
                .andExpect(jsonPath("$.email").value("bob@example.com"))
                .andExpect(jsonPath("$.displayName").value("Bob"));

        verify(authenticationService).login("bob@example.com", "correctPassword123");
    }

    @Test
    @DisplayName("login: missing body returns 400 Bad Request")
    void login_missingBody_returns400BadRequest() throws Exception {
        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
    }

    @Test
    @DisplayName("login: blank email returns 400 Bad Request")
    void login_blankEmail_returns400BadRequest() throws Exception {
        LoginRequest request = new LoginRequest("   ", "password123");

        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
    }

    @Test
    @DisplayName("login: blank password returns 400 Bad Request")
    void login_blankPassword_returns400BadRequest() throws Exception {
        LoginRequest request = new LoginRequest("bob@example.com", "   ");

        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
    }

    @Test
    @DisplayName("login: bad credentials throws BadCredentialsException and returns 401 Unauthorized")
    void login_badCredentials_returns401Unauthorized() throws Exception {
        when(authenticationService.login("bob@example.com", "wrongPassword"))
                .thenThrow(new BadCredentialsException("Invalid email or password"));

        LoginRequest request = new LoginRequest("bob@example.com", "wrongPassword");

        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
    }

    @Test
    @DisplayName("logout: with existing session invalidates session and clears JSESSIONID cookie")
    void logout_withExistingSession_invalidatesSessionAndClearsCookie() throws Exception {
        MockHttpSession session = new MockHttpSession();

        mockMvc.perform(post("/api/auth/logout")
                        .session(session))
                .andExpect(status().isNoContent())
                .andExpect(cookie().value("JSESSIONID", ""))
                .andExpect(cookie().maxAge("JSESSIONID", 0));
    }

    @Test
    @DisplayName("logout: without existing session returns 204 No Content")
    void logout_withoutSession_returns204NoContent() throws Exception {
        mockMvc.perform(post("/api/auth/logout"))
                .andExpect(status().isNoContent())
                .andExpect(cookie().value("JSESSIONID", ""))
                .andExpect(cookie().maxAge("JSESSIONID", 0));
    }

    @Test
    @DisplayName("me: unauthenticated access returns 403 Forbidden under SecurityConfig")
    void me_unauthenticated_returns403Forbidden() throws Exception {
        mockMvc.perform(get("/api/auth/me"))
                .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(username = "charlie@example.com")
    @DisplayName("me: authenticated access returns 200 OK with UserResponse")
    void me_authenticated_returns200OkWithUserProfile() throws Exception {
        UUID userId = UUID.randomUUID();
        User user = new User("charlie@example.com");
        user.setId(userId);
        user.setDisplayName("Charlie");
        user.setStatus(UserStatus.ACTIVE);

        when(userRepository.findByEmail("charlie@example.com")).thenReturn(Optional.of(user));

        mockMvc.perform(get("/api/auth/me"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(userId.toString()))
                .andExpect(jsonPath("$.email").value("charlie@example.com"))
                .andExpect(jsonPath("$.displayName").value("Charlie"));
    }

    @Test
    @WithMockUser(username = "charlie@example.com")
    @DisplayName("me: authenticated user not found in repository returns 404 Not Found")
    void me_userNotFound_returns404NotFound() throws Exception {
        when(userRepository.findByEmail("charlie@example.com")).thenReturn(Optional.empty());

        mockMvc.perform(get("/api/auth/me"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }

    @Test
    @DisplayName("register: GET method returns 405 Method Not Allowed")
    void register_getMethod_returns405MethodNotAllowed() throws Exception {
        mockMvc.perform(get("/api/auth/register"))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(jsonPath("$.code").value("METHOD_NOT_ALLOWED"));
    }

    @Test
    @DisplayName("login: GET method returns 405 Method Not Allowed")
    void login_getMethod_returns405MethodNotAllowed() throws Exception {
        mockMvc.perform(get("/api/auth/login"))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(jsonPath("$.code").value("METHOD_NOT_ALLOWED"));
    }

    @Test
    @WithMockUser(username = "charlie@example.com")
    @DisplayName("me: DELETE method returns 405 Method Not Allowed")
    void me_deleteMethod_returns405MethodNotAllowed() throws Exception {
        mockMvc.perform(delete("/api/auth/me")
                        .with(csrf()))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(jsonPath("$.code").value("METHOD_NOT_ALLOWED"));
    }
}
