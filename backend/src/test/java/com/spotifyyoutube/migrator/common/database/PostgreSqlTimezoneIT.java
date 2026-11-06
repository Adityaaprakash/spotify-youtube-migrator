package com.spotifyyoutube.migrator.common.database;

import com.spotifyyoutube.migrator.common.config.JpaConfig;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.TimeZone;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
@Import(JpaConfig.class)
@DisplayName("Regression Protection: PostgreSQL Testcontainers Timezone Integration Test")
public class PostgreSqlTimezoneIT {

    @Autowired
    private DataSource dataSource;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    @DisplayName("PostgreSQL JDBC connection initializes successfully without TimeZone rejection")
    void postgreSqlConnectionInitializesSuccessfully() throws SQLException {
        assertThat(dataSource).isNotNull();
        try (Connection connection = dataSource.getConnection()) {
            assertThat(connection.isValid(5)).isTrue();
        }
    }

    @Test
    @DisplayName("PostgreSQL server TimeZone parameter resolves to UTC")
    void postgreSqlServerTimeZoneIsUtc() {
        String dbTimeZone = jdbcTemplate.queryForObject("SELECT current_setting('TimeZone')", String.class);
        assertThat(dbTimeZone)
                .as("PostgreSQL server session timezone must be UTC")
                .isEqualTo("UTC");
    }

    @Test
    @DisplayName("Test JVM default timezone is deterministic UTC in integration test environment")
    void testJvmTimezoneIsUtc() {
        assertThat(TimeZone.getDefault().getID())
                .as("Integration test JVM default timezone must be UTC")
                .isEqualTo("UTC");
    }
}
