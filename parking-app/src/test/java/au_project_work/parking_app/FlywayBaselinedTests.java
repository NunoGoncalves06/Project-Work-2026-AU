package au_project_work.parking_app;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

// database created by db/init (the existing config): Flyway must baseline it at 1 and apply V2
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class FlywayBaselinedTests {

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void existingDatabaseIsBaselinedAtV1AndGetsV2AndV3() {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT version, type, success FROM flyway_schema_history ORDER BY installed_rank");
        assertEquals(3, rows.size());
        assertEquals("1", rows.get(0).get("version"));
        assertEquals("BASELINE", rows.get(0).get("type"));
        assertEquals("2", rows.get(1).get("version"));
        assertEquals("3", rows.get(2).get("version"));
        assertEquals("SQL", rows.get(1).get("type"));
        assertEquals(true, rows.get(1).get("success"));
        assertEquals(true, rows.get(2).get("success"));
    }
}
