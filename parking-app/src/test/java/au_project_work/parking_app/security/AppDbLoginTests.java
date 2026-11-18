package au_project_work.parking_app.security;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.configuration.FluentConfiguration;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

class AppDbLoginTests {

    private static FluentConfiguration password(String password) {
        return Flyway.configure().placeholders(Map.of("app_db_password", password));
    }

    @Test
    void acceptsNoLoginOrAStrongPassword() {
        new AppDbLogin("postgres").customize(password(""));
        new AppDbLogin("parking_app").customize(password("abcDEF0123456789"));
    }

    @Test
    void refusesOtherPasswordsWithoutLoggingThem() {
        for (String bad : List.of("abcDEF012345678", "abcDEF0123456789'", "abcDEF0123456789 x", "abcDEF0123456789$")) {
            IllegalStateException e = assertThrows(IllegalStateException.class,
                    () -> new AppDbLogin("parking_app").customize(password(bad)));
            assertFalse(e.getMessage().contains(bad));
        }
    }

    @Test
    void appLoginWithoutPasswordStopsStartUp() {
        assertThrows(IllegalStateException.class, () -> new AppDbLogin("parking_app").customize(password("")));
    }
}
