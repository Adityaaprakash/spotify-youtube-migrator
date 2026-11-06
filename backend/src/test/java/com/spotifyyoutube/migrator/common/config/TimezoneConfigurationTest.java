package com.spotifyyoutube.migrator.common.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.TimeZone;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("Regression Protection: Deterministic JVM Timezone Configuration Test")
class TimezoneConfigurationTest {

    @Test
    @DisplayName("JVM user.timezone system property must be explicitly UTC")
    void jvmUserTimezonePropertyIsUtc() {
        String userTimezone = System.getProperty("user.timezone");
        assertThat(userTimezone)
                .as("user.timezone system property must be explicitly configured to UTC")
                .isEqualTo("UTC");
    }

    @Test
    @DisplayName("Default TimeZone ID must be UTC")
    void defaultTimeZoneIsUtc() {
        TimeZone defaultTz = TimeZone.getDefault();
        assertThat(defaultTz.getID())
                .as("Default TimeZone ID must be UTC to prevent PostgreSQL startup rejection")
                .isEqualTo("UTC");
    }

    @Test
    @DisplayName("Default ZoneId must resolve to UTC")
    void defaultZoneIdIsUtc() {
        ZoneId zoneId = ZoneId.systemDefault();
        assertThat(zoneId.normalized())
                .as("Default ZoneId normalized offset must equal ZoneOffset.UTC")
                .isEqualTo(ZoneOffset.UTC);
    }
}
