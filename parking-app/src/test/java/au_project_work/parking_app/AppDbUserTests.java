package au_project_work.parking_app;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistrar;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.MountableFile;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

// wired like docker-compose.prod.yml: Flyway runs as the owner, the app as parking_app (made by afterMigrate.sql)
@SpringBootTest(properties = "APP_DB_PASSWORD=" + AppDbUserTests.PASSWORD)
@Import(AppDbUserTests.Db.class)
class AppDbUserTests {

    static final String PASSWORD = "app0db0test0password";

    @TestConfiguration(proxyBeanMethods = false)
    static class Db {
        @Bean
        PostgreSQLContainer postgres() {
            return new PostgreSQLContainer("postgres:16")
                    .withCopyFileToContainer(MountableFile.forHostPath("../db/init"), "/docker-entrypoint-initdb.d/");
        }

        @Bean
        DynamicPropertyRegistrar dbLogins(PostgreSQLContainer postgres) {
            return registry -> {
                registry.add("spring.datasource.url", postgres::getJdbcUrl);
                registry.add("spring.datasource.username", () -> "parking_app");
                registry.add("spring.datasource.password", () -> PASSWORD);
                registry.add("spring.flyway.user", postgres::getUsername);
                registry.add("spring.flyway.password", postgres::getPassword);
            };
        }
    }

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private PostgreSQLContainer postgres;

    @Autowired
    private Flyway flyway;

    private JdbcTemplate owner() {
        return new JdbcTemplate(new SingleConnectionDataSource(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword(), true));
    }

    @Test
    void appUsesRowsButCannotChangeTheSchema() {
        assertEquals("parking_app", jdbc.queryForObject("SELECT current_user", String.class));
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM parking_lots", Integer.class));
        Long id = jdbc.queryForObject("""
                INSERT INTO parking_spot_measurements (parking_spot_sensor_id, occupied) VALUES (1, true)
                RETURNING parking_spot_measurement_id""", Long.class);
        assertEquals(1, jdbc.update("DELETE FROM parking_spot_measurements WHERE parking_spot_measurement_id = ?", id));

        assertThrows(DataAccessException.class, () -> jdbc.execute("DROP TABLE parking_spot_measurements"));
        assertThrows(DataAccessException.class, () -> jdbc.execute("ALTER TABLE parking_lots ADD COLUMN x int"));
        assertThrows(DataAccessException.class, () -> jdbc.execute("CREATE TABLE x (x int)"));
        assertThrows(DataAccessException.class, () -> jdbc.execute("DELETE FROM flyway_schema_history"));
    }

    @Test
    void tablesFromLaterMigrationsAreUsableToo() {
        owner().execute("CREATE TABLE later_table (x int)");
        try {
            assertEquals(1, jdbc.update("INSERT INTO later_table VALUES (1)"));
        } finally {
            owner().execute("DROP TABLE later_table");
        }
    }

    // a dump restored onto a fresh volume brings the full history but no roles: the next start must recreate the login
    @Test
    @DirtiesContext // the app's open connections belong to the dropped role
    void nextStartRecreatesAMissingLogin() {
        JdbcTemplate owner = owner();
        assertEquals(0, flyway.migrate().migrationsExecuted); // login exists: the callback just resets it
        owner.execute("DROP OWNED BY parking_app");
        owner.execute("DROP ROLE parking_app");

        assertEquals(0, flyway.migrate().migrationsExecuted); // history complete: only the callback runs
        JdbcTemplate app = new JdbcTemplate(
                new SingleConnectionDataSource(postgres.getJdbcUrl(), "parking_app", PASSWORD, true));
        assertEquals(1, app.queryForObject("SELECT count(*) FROM parking_lots", Integer.class));
        assertThrows(DataAccessException.class, () -> app.execute("DROP TABLE parking_lots"));
    }

    @Test
    void passwordIsNotStoredInTheMigrationHistory() {
        JdbcTemplate owner = owner();
        assertEquals(0, owner.queryForObject(
                "SELECT count(*) FROM flyway_schema_history h WHERE row_to_json(h)::text LIKE ?", Integer.class,
                "%" + PASSWORD + "%"));
    }
}
