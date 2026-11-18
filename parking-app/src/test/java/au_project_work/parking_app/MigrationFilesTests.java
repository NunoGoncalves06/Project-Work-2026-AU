package au_project_work.parking_app;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;

class MigrationFilesTests {

    // V1 must stay identical to what db/init gives local dev, or a baselined DB and a fresh one diverge
    @Test
    void v1BaselineEqualsDbInitCreateScript() throws Exception {
        assertArrayEquals(
                Files.readAllBytes(Path.of("../db/init/01_create.sql")),
                Files.readAllBytes(Path.of("src/main/resources/db/migration/V1__baseline.sql")));
    }
}
