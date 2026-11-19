package com.spotifyyoutube.migrator.common.security;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
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
        "management.endpoint.health.probes.enabled=true",
        "management.endpoint.health.group.liveness.include=livenessState",
        "management.endpoint.health.group.readiness.include=readinessState,db"
})
@DisplayName("Phase 18B: Actuator Database Outage Readiness and Liveness Isolation Tests")
class ActuatorReadinessIsolationTest {

    @TestConfiguration
    static class FailingDbHealthConfig {
        @Bean(name = "dbHealthIndicator")
        @Primary
        public HealthIndicator dbHealthIndicator() {
            return () -> Health.down()
                    .withDetail("error", "Simulated connection timeout to postgresql:5432")
                    .build();
        }
    }

    @Autowired
    private MockMvc mockMvc;

    @Test
    @WithAnonymousUser
    @DisplayName("When database is DOWN: readiness probe returns 503 Service Unavailable with masked details")
    void databaseDown_ReadinessReturns503WithMaskedDetails() throws Exception {
        mockMvc.perform(get("/actuator/health/readiness"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.status").value("DOWN"))
                .andExpect(jsonPath("$.components").doesNotExist())
                .andExpect(jsonPath("$.details").doesNotExist())
                .andExpect(jsonPath("$.error").doesNotExist());
    }

    @Test
    @WithAnonymousUser
    @DisplayName("When database is DOWN: liveness probe remains 200 OK (database outage does NOT kill application process)")
    void databaseDown_LivenessRemains200Ok() throws Exception {
        mockMvc.perform(get("/actuator/health/liveness"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"))
                .andExpect(jsonPath("$.components").doesNotExist())
                .andExpect(jsonPath("$.details").doesNotExist());
    }

    @Test
    @WithAnonymousUser
    @DisplayName("When database is DOWN: aggregate health endpoint returns 503 with masked details")
    void databaseDown_AggregateHealthReturns503WithMaskedDetails() throws Exception {
        mockMvc.perform(get("/actuator/health"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.status").value("DOWN"))
                .andExpect(jsonPath("$.components").doesNotExist())
                .andExpect(jsonPath("$.details").doesNotExist())
                .andExpect(jsonPath("$.db").doesNotExist());
    }
}
