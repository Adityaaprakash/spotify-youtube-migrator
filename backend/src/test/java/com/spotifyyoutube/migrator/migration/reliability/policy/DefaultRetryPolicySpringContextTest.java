package com.spotifyyoutube.migrator.migration.reliability.policy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;

import static org.assertj.core.api.Assertions.assertThat;

@SpringJUnitConfig(classes = {
        BackoffCalculator.class,
        DefaultRetryPolicy.class
})
@DisplayName("Phase 13C: Spring Context Dependency Injection Tests")
class DefaultRetryPolicySpringContextTest {

    @Autowired
    private RetryPolicy retryPolicy;

    @Autowired
    private DefaultRetryPolicy defaultRetryPolicy;

    @Autowired
    private BackoffCalculator backoffCalculator;

    @Test
    @DisplayName("Spring application context constructs and autowires DefaultRetryPolicy with BackoffCalculator")
    void contextLoadsAndWiresBeans() {
        assertThat(retryPolicy).isNotNull();
        assertThat(defaultRetryPolicy).isNotNull();
        assertThat(backoffCalculator).isNotNull();
        assertThat(defaultRetryPolicy.getBackoffCalculator()).isSameAs(backoffCalculator);
    }
}
