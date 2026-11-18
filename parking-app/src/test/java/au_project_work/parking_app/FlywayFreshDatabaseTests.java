package au_project_work.parking_app;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

// empty database, no db/init mount: Flyway alone must build the whole schema
@SpringBootTest
@Import(FlywayFreshDatabaseTests.EmptyDb.class)
class FlywayFreshDatabaseTests {

    @TestConfiguration(proxyBeanMethods = false)
    static class EmptyDb {
        @Bean
        @ServiceConnection
        PostgreSQLContainer postgres() {
            return new PostgreSQLContainer("postgres:16");
        }
    }

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void emptyDatabaseGetsV1AndV2AndV3() {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT version, type, success FROM flyway_schema_history ORDER BY installed_rank");
        assertEquals(3, rows.size());
        // no APP_DB_PASSWORD in tests: afterMigrate.sql must skip creating the app login
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM pg_roles WHERE rolname = 'parking_app'", Integer.class));
        assertEquals("1", rows.get(0).get("version"));
        assertEquals("SQL", rows.get(0).get("type"));
        assertEquals("2", rows.get(1).get("version"));
        assertEquals("3", rows.get(2).get("version"));
        assertEquals("SQL", rows.get(1).get("type"));
        assertEquals(true, rows.get(0).get("success"));
        assertEquals(true, rows.get(1).get("success"));
        assertEquals(true, rows.get(2).get("success"));
    }
}
