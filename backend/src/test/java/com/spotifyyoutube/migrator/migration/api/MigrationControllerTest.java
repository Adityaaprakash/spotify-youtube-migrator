package com.spotifyyoutube.migrator.migration.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.spotifyyoutube.migrator.common.exception.InvalidStateException;
import com.spotifyyoutube.migrator.common.exception.ResourceNotFoundException;
import com.spotifyyoutube.migrator.identity.domain.User;
import com.spotifyyoutube.migrator.migration.api.dto.CreateMigrationRequest;
import com.spotifyyoutube.migrator.migration.application.MigrationService;
import com.spotifyyoutube.migrator.migration.domain.MigrationJob;
import com.spotifyyoutube.migrator.migration.domain.MigrationStatus;
import com.spotifyyoutube.migrator.playlist.domain.Platform;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import com.spotifyyoutube.migrator.common.config.SecurityConfig;
import com.spotifyyoutube.migrator.common.exception.GlobalExceptionHandler;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(MigrationController.class)
@Import({SecurityConfig.class, GlobalExceptionHandler.class})
public class MigrationControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private MigrationService migrationService;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    public void testCreateMigration_Success() throws Exception {
        UUID userId = UUID.randomUUID();
        CreateMigrationRequest request = new CreateMigrationRequest(
                userId, "spotify-pl-1", Platform.SPOTIFY, Platform.YOUTUBE);

        User mockUser = new User("test@test.com");
        MigrationJob mockJob = new MigrationJob();
        mockJob.setUser(mockUser);
        mockJob.setSourcePlaylistId("spotify-pl-1");
        mockJob.setSourcePlatform(Platform.SPOTIFY);
        mockJob.setTargetPlatform(Platform.YOUTUBE);
        mockJob.setStatus(MigrationStatus.PENDING);

        when(migrationService.createMigrationJob(any())).thenReturn(mockJob);

        mockMvc.perform(post("/api/migrations")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.sourcePlaylistId").value("spotify-pl-1"));
    }

    @Test
    public void testCreateMigration_ValidationError() throws Exception {
        // Missing source platform violates @NotNull
        CreateMigrationRequest request = new CreateMigrationRequest(
                UUID.randomUUID(), "spotify-pl-1", null, Platform.YOUTUBE);

        mockMvc.perform(post("/api/migrations")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
    }

    @Test
    public void testCreateMigration_InvalidState() throws Exception {
        CreateMigrationRequest request = new CreateMigrationRequest(
                UUID.randomUUID(), "spotify-pl-1", Platform.SPOTIFY, Platform.SPOTIFY);

        when(migrationService.createMigrationJob(any()))
                .thenThrow(new InvalidStateException("Source and target platforms must be logically different"));

        mockMvc.perform(post("/api/migrations")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INVALID_STATE"))
                .andExpect(jsonPath("$.message").value("Source and target platforms must be logically different"));
    }

    @Test
    public void testCreateMigration_UserNotFound() throws Exception {
        CreateMigrationRequest request = new CreateMigrationRequest(
                UUID.randomUUID(), "spotify-pl-1", Platform.SPOTIFY, Platform.YOUTUBE);

        when(migrationService.createMigrationJob(any()))
                .thenThrow(new ResourceNotFoundException("User not found"));

        mockMvc.perform(post("/api/migrations")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"))
                .andExpect(jsonPath("$.message").value("User not found"));
    }
}
