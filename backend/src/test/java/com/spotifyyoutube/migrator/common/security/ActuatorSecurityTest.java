package com.spotifyyoutube.migrator.common.security;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.test.context.support.WithAnonymousUser;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestPropertySource(properties = {
        "management.endpoints.web.exposure.include=health,info,prometheus",
        "management.endpoint.health.show-details=never",
        "management.endpoint.health.probes.enabled=true",
        "management.endpoint.health.group.liveness.include=livenessState",
        "management.endpoint.health.group.readiness.include=readinessState,db"
})
@DisplayName("Phase 18B: Actuator Operational Endpoint and Health Security Tests")
class ActuatorSecurityTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    @WithAnonymousUser
    @DisplayName("Anonymous access to /actuator/health succeeds with basic status and no internal component details")
    void anonymousHealthEndpoint_ShowsStatusWithoutSensitiveDetails() throws Exception {
        mockMvc.perform(get("/actuator/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"))
                .andExpect(jsonPath("$.components").doesNotExist())
                .andExpect(jsonPath("$.details").doesNotExist())
                .andExpect(jsonPath("$.diskSpace").doesNotExist())
                .andExpect(jsonPath("$.db").doesNotExist());
    }

    @Test
    @WithMockUser
    @DisplayName("Authenticated access to /actuator/health also masks internal component details under show-details: never")
    void authenticatedHealthEndpoint_ShowsStatusWithoutSensitiveDetails() throws Exception {
        mockMvc.perform(get("/actuator/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"))
                .andExpect(jsonPath("$.components").doesNotExist())
                .andExpect(jsonPath("$.details").doesNotExist())
                .andExpect(jsonPath("$.diskSpace").doesNotExist())
                .andExpect(jsonPath("$.db").doesNotExist());
    }

    @Test
    @WithAnonymousUser
    @DisplayName("Anonymous access to liveness probe /actuator/health/liveness succeeds and masks component details")
    void anonymousLivenessProbe_AllowedAndMasked() throws Exception {
        mockMvc.perform(get("/actuator/health/liveness"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"))
                .andExpect(jsonPath("$.components").doesNotExist())
                .andExpect(jsonPath("$.details").doesNotExist());
    }

    @Test
    @WithAnonymousUser
    @DisplayName("Anonymous access to readiness probe /actuator/health/readiness succeeds and masks component details")
    void anonymousReadinessProbe_AllowedAndMasked() throws Exception {
        mockMvc.perform(get("/actuator/health/readiness"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"))
                .andExpect(jsonPath("$.components").doesNotExist())
                .andExpect(jsonPath("$.details").doesNotExist());
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
    @DisplayName("Anonymous access to /actuator/prometheus is permitted for metrics scraping and returns 200 OK")
    void anonymousPrometheusEndpoint_Allowed() throws Exception {
        mockMvc.perform(get("/actuator/prometheus"))
                .andExpect(status().isOk());
    }

    @Test
    @WithAnonymousUser
    @DisplayName("Non-GET (POST) request to /actuator/prometheus is rejected by security configuration")
    void postPrometheusEndpoint_Forbidden() throws Exception {
        mockMvc.perform(post("/actuator/prometheus"))
                .andExpect(status().isForbidden());
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
    @DisplayName("Sensitive actuator endpoints (/actuator/env, /actuator/beans, /actuator/heapdump, /actuator/threaddump, /actuator/configprops, /actuator/loggers, /actuator/mappings) are not accessible")
    void sensitiveEndpoints_NotAccessible() throws Exception {
        mockMvc.perform(get("/actuator/env"))
                .andExpect(status().isForbidden());

        mockMvc.perform(get("/actuator/beans"))
                .andExpect(status().isForbidden());

        mockMvc.perform(get("/actuator/heapdump"))
                .andExpect(status().isForbidden());

        mockMvc.perform(get("/actuator/threaddump"))
                .andExpect(status().isForbidden());

        mockMvc.perform(get("/actuator/configprops"))
                .andExpect(status().isForbidden());

        mockMvc.perform(get("/actuator/loggers"))
                .andExpect(status().isForbidden());

        mockMvc.perform(get("/actuator/mappings"))
                .andExpect(status().isForbidden());
    }

    @Test
    @WithAnonymousUser
    @DisplayName("Non-GET (POST) request to /actuator/health is rejected by security configuration")
    void postHealthEndpoint_Forbidden() throws Exception {
        mockMvc.perform(post("/actuator/health"))
                .andExpect(status().isForbidden());
    }
}
