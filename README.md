# Project-Work-2026-AU


Test DB:

- Requirements: Docker with Compose
- Start and Initialize db Container with: `docker compose up -d`
- To test db initialization run select_checks SQL File with: `docker compose exec db sh -c 'psql -U "$POSTGRES_USER" -d "$POSTGRES_DB" -f /scripts/select_checks.sql'`
- To reset database use: `docker compose down -v` (deletes the volume). Restart db with `docker compose up -d`
