package com.spotifyyoutube.migrator.common.metrics;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.test.context.support.WithAnonymousUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestPropertySource(properties = {
        "management.prometheus.metrics.export.enabled=true"
})
@DisplayName("Phase 18C: Metrics Infrastructure & Registry Integration Tests")
class MetricsInfrastructureIntegrationTest {

    @Autowired
    private MeterRegistry meterRegistry;

    @Autowired(required = false)
    private PrometheusMeterRegistry prometheusMeterRegistry;

    @Autowired
    private MockMvc mockMvc;

    @Test
    @DisplayName("MeterRegistry bean is auto-configured and injectable in Spring application context")
    void meterRegistryInjected() {
        assertThat(meterRegistry).isNotNull();
    }

    @Test
    @DisplayName("PrometheusMeterRegistry is available in the Spring application context when export is enabled")
    void prometheusMeterRegistryAvailable() {
        assertThat(prometheusMeterRegistry).isNotNull();
    }

    @Test
    @DisplayName("commonTagsMeterFilter attaches application tag to newly registered meters")
    void commonTagsFilterApplies() {
        Counter counter = meterRegistry.counter("test.infrastructure.counter");
        assertThat(counter.getId().getTag("application")).isEqualTo("spotify-youtube-migrator");
    }

    @Test
    @WithAnonymousUser
    @DisplayName("Prometheus scraping endpoint /actuator/prometheus is exposed and returns 200 OK with Prometheus metrics")
    void prometheusEndpoint_Exposed_ReturnsOk() throws Exception {
        mockMvc.perform(get("/actuator/prometheus"))
                .andExpect(status().isOk())
                .andExpect(result -> {
                    String content = result.getResponse().getContentAsString();
                    assertThat(content).contains("application=\"spotify-youtube-migrator\"");
                });
    }

    @Test
    @WithAnonymousUser
    @DisplayName("Metrics endpoint /actuator/metrics is not exposed and returns 403 Forbidden")
    void metricsEndpoint_NotExposed_Forbidden() throws Exception {
        mockMvc.perform(get("/actuator/metrics"))
                .andExpect(status().isForbidden());
    }
}
