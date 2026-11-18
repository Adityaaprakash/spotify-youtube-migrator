package com.spotifyyoutube.migrator.common.security;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.test.context.support.WithAnonymousUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestPropertySource(properties = {
        "management.endpoints.web.exposure.include=health,info",
        "management.endpoint.health.show-details=never",
        "management.endpoint.health.probes.enabled=true"
})
@DisplayName("Phase 16F: Actuator Operational Endpoint Security Tests")
class ActuatorSecurityTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    @WithAnonymousUser
    @DisplayName("Anonymous access to /actuator/health succeeds with basic status and no internal component details")
    void anonymousHealthEndpoint_ShowsStatusWithoutSensitiveDetails() throws Exception {
        mockMvc.perform(get("/actuator/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").exists())
                .andExpect(jsonPath("$.components").doesNotExist())
                .andExpect(jsonPath("$.details").doesNotExist())
                .andExpect(jsonPath("$.diskSpace").doesNotExist())
                .andExpect(jsonPath("$.db").doesNotExist());
    }

    @Test
    @WithAnonymousUser
    @DisplayName("Anonymous access to liveness probe /actuator/health/liveness succeeds")
    void anonymousLivenessProbe_Allowed() throws Exception {
        mockMvc.perform(get("/actuator/health/liveness"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").exists());
    }

    @Test
    @WithAnonymousUser
    @DisplayName("Anonymous access to readiness probe /actuator/health/readiness succeeds")
    void anonymousReadinessProbe_Allowed() throws Exception {
        mockMvc.perform(get("/actuator/health/readiness"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").exists());
    }

    @Test
    @WithAnonymousUser
    @DisplayName("Anonymous access to /actuator/info is permitted")
    void anonymousInfoEndpoint_Allowed() throws Exception {
        mockMvc.perform(get("/actuator/info"))
                .andExpect(status().isOk());
    }

    @Test
    @WithAnonymousUser
    @DisplayName("Anonymous access to non-whitelisted actuator endpoints (/actuator/metrics) is blocked with 403 Forbidden")
    void anonymousMetricsEndpoint_Forbidden() throws Exception {
        mockMvc.perform(get("/actuator/metrics"))
                .andExpect(status().isForbidden());
    }

    @Test
    @WithAnonymousUser
    @DisplayName("Sensitive actuator endpoints (/actuator/env, /actuator/beans, /actuator/heapdump) are not accessible")
    void sensitiveEndpoints_NotAccessible() throws Exception {
        mockMvc.perform(get("/actuator/env"))
                .andExpect(status().isForbidden());

        mockMvc.perform(get("/actuator/beans"))
                .andExpect(status().isForbidden());

        mockMvc.perform(get("/actuator/heapdump"))
                .andExpect(status().isForbidden());
    }
}
