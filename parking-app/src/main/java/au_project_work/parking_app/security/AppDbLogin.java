package au_project_work.parking_app.security;

import org.flywaydb.core.api.configuration.FluentConfiguration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.flyway.autoconfigure.FlywayConfigurationCustomizer;
import org.springframework.stereotype.Component;

/**
 * Checks APP_DB_PASSWORD before Flyway runs afterMigrate.sql, which creates the parking_app login with it.
 * Messages never contain the value: they end up in the logs.
 */
@Component
class AppDbLogin implements FlywayConfigurationCustomizer {

    private final String datasourceUser;

    AppDbLogin(@Value("${spring.datasource.username:}") String datasourceUser) {
        this.datasourceUser = datasourceUser;
    }

    @Override
    public void customize(FluentConfiguration configuration) {
        String password = configuration.getPlaceholders().getOrDefault("app_db_password", "");
        // also keeps the value from breaking out of the SQL string it is put in
        if (!password.isEmpty() && !password.matches("[A-Za-z0-9]{16,}")) {
            throw new IllegalStateException("APP_DB_PASSWORD must be 16 or more letters and digits (A-Z, a-z, 0-9)");
        }
        if (password.isEmpty() && "parking_app".equals(datasourceUser)) {
            throw new IllegalStateException(
                    "SPRING_DATASOURCE_USERNAME is parking_app but APP_DB_PASSWORD is empty: set it in .env");
        }
    }
}
