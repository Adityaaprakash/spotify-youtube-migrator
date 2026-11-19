package com.spotifyyoutube.migrator.common.web;

import com.spotifyyoutube.migrator.common.config.SecurityConfig;
import com.spotifyyoutube.migrator.common.exception.GlobalExceptionHandler;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(HealthController.class)
@Import({SecurityConfig.class, GlobalExceptionHandler.class})
@DisplayName("HealthController WebMvc Tests")
class HealthControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    @WithMockUser
    @DisplayName("health: authenticated request returns 200 OK with UP status")
    void health_authenticated_returns200OkWithStatusUp() throws Exception {
        mockMvc.perform(get("/api/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"));
    }

    @Test
    @DisplayName("health: unauthenticated request returns 403 Forbidden under SecurityConfig")
    void health_unauthenticated_returns403Forbidden() throws Exception {
        mockMvc.perform(get("/api/health"))
                .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser
    @DisplayName("health: POST method returns 405 Method Not Allowed")
    void health_postMethod_returns405MethodNotAllowed() throws Exception {
        mockMvc.perform(post("/api/health")
                        .with(csrf()))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(jsonPath("$.code").value("METHOD_NOT_ALLOWED"));
    }
}
