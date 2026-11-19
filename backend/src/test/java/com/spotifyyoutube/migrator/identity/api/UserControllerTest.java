package com.spotifyyoutube.migrator.identity.api;

import com.spotifyyoutube.migrator.common.config.SecurityConfig;
import com.spotifyyoutube.migrator.common.exception.GlobalExceptionHandler;
import com.spotifyyoutube.migrator.common.exception.ResourceNotFoundException;
import com.spotifyyoutube.migrator.identity.application.UserService;
import com.spotifyyoutube.migrator.identity.domain.User;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.util.UUID;

import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.springframework.security.test.context.support.WithMockUser;

@WebMvcTest(UserController.class)
@Import({SecurityConfig.class, GlobalExceptionHandler.class})
@WithMockUser
public class UserControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private UserService userService;

    @Test
    public void testGetUser_Success() throws Exception {
        UUID userId = UUID.randomUUID();
        User mockUser = new User("test@test.com");
        mockUser.setId(userId);
        
        when(userService.getUserById(userId)).thenReturn(mockUser);

        mockMvc.perform(get("/api/users/" + userId)
                .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(userId.toString()))
                .andExpect(jsonPath("$.email").value("test@test.com"));
    }

    @Test
    public void testGetUser_NotFound() throws Exception {
        UUID userId = UUID.randomUUID();
        when(userService.getUserById(userId)).thenThrow(new ResourceNotFoundException("User not found"));

        mockMvc.perform(get("/api/users/" + userId)
                .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }

    @Test
    public void testGetUser_InvalidUuid_ReturnsBadRequest() throws Exception {
        mockMvc.perform(get("/api/users/not-a-valid-uuid")
                .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
                .andExpect(jsonPath("$.message").value("Invalid parameter value for: id"));
    }

    @Test
    @WithMockUser(username = "attacker@test.com")
    public void testGetUser_CrossUserAccess_ReturnsNotFoundForIdorProtection() throws Exception {
        UUID targetUserId = UUID.randomUUID();
        User targetUser = new User("victim@test.com");
        targetUser.setId(targetUserId);

        UUID attackerId = UUID.randomUUID();
        User attacker = new User("attacker@test.com");
        attacker.setId(attackerId);

        when(userService.getUserById(targetUserId)).thenReturn(targetUser);
        when(userService.getUserByEmail("attacker@test.com")).thenReturn(attacker);

        mockMvc.perform(get("/api/users/" + targetUserId)
                .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"))
                .andExpect(jsonPath("$.message").value("User not found with id: " + targetUserId));
    }

    @Test
    public void testGetUser_PostMethod_ReturnsMethodNotAllowed() throws Exception {
        UUID userId = UUID.randomUUID();
        mockMvc.perform(post("/api/users/" + userId)
                        .with(csrf()))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(jsonPath("$.code").value("METHOD_NOT_ALLOWED"));
    }

    @Test
    public void testGetUser_DeleteMethod_ReturnsMethodNotAllowed() throws Exception {
        UUID userId = UUID.randomUUID();
        mockMvc.perform(delete("/api/users/" + userId)
                        .with(csrf()))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(jsonPath("$.code").value("METHOD_NOT_ALLOWED"));
    }
}
