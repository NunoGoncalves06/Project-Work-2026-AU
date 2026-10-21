# Project-Work-2026-AU


Run application:

- Requirements: Docker with Compose
- Start application (parking-app container and db container) with: `docker compose up` 
- if you want to (re)build the image use: `docker compose up --build`
- application is then available under 'http://localhost/8080'
- Stop application with: `docker compose down`
- To test db initialization run select_checks SQL File in running container with: `docker compose exec db sh -c 'psql -U "$POSTGRES_USER" -d "$POSTGRES_DB" -f /scripts/select_checks.sql'`
