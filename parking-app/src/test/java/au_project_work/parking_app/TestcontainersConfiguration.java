package au_project_work.parking_app;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.MountableFile;

@TestConfiguration(proxyBeanMethods = false)
class TestcontainersConfiguration {

    // runs the same init scripts as docker compose, so tests use the real schema + seed
    @Bean
    @ServiceConnection
    PostgreSQLContainer postgres() {
        return new PostgreSQLContainer("postgres:16")
                .withCopyFileToContainer(MountableFile.forHostPath("../db/init"), "/docker-entrypoint-initdb.d/");
    }
}
