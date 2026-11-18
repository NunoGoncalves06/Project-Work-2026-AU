-- Flyway callback, run after every migrate (each start), so it repairs what history can't: a dump restored onto a
-- fresh volume has no roles, and a changed APP_DB_PASSWORD takes effect on the next start.
-- parking_app is the backend's least-privilege login: it reads and writes rows but can't create, alter or drop.
-- Skipped while APP_DB_PASSWORD is empty (local dev, tests stay on postgres). AppDbLogin checked the password is
-- 16+ letters and digits, so this statement can't fail on it and get logged; it is kept apart from the grants.
DO $$
BEGIN
    IF '${app_db_password}' = '' THEN
        RETURN;
    END IF;
    IF EXISTS (SELECT FROM pg_roles WHERE rolname = 'parking_app') THEN
        ALTER ROLE parking_app WITH LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOBYPASSRLS NOREPLICATION
            PASSWORD '${app_db_password}';
    ELSE
        CREATE ROLE parking_app WITH LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOBYPASSRLS NOREPLICATION
            PASSWORD '${app_db_password}';
    END IF;
END
$$;

DO $$
BEGIN
    IF NOT EXISTS (SELECT FROM pg_roles WHERE rolname = 'parking_app') THEN
        RETURN;
    END IF;
    GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA public TO parking_app;
    GRANT USAGE, SELECT, UPDATE ON ALL SEQUENCES IN SCHEMA public TO parking_app;
    -- tables and sequences that later migrations create (as this same owner) get the same rights
    ALTER DEFAULT PRIVILEGES IN SCHEMA public GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO parking_app;
    ALTER DEFAULT PRIVILEGES IN SCHEMA public GRANT USAGE, SELECT, UPDATE ON SEQUENCES TO parking_app;
    -- the migration history is Flyway's alone
    REVOKE ALL ON ${flyway:table} FROM parking_app;
END
$$;
