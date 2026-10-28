# Project-Work-2026-AU


Run application:

- Requirements: Docker with Compose
- Start application (parking-app container and db container) with: `docker compose up`
- if you want to (re)build the image use: `docker compose up --build`
- application is then available under http://localhost:8080
- Stop application with: `docker compose down`
- The DB init scripts only run on an empty volume. After changing `db/init`, reset with: `docker compose down -v` (deletes DB data)
- To test db initialization run select_checks SQL File in running container with: `docker compose exec db sh -c 'psql -U "$POSTGRES_USER" -d "$POSTGRES_DB" -f /scripts/select_checks.sql'`
- If port 5432 is already used by a local PostgreSQL, stop it or change the db port mapping (e.g. "5433:5432") in docker-compose.yml
- Run backend tests (needs Docker): `cd parking-app && ./gradlew test`

REST API:

| Method | Path | Body | Use |
|---|---|---|---|
| GET | `/parking-lots` | | all lots with totalSpots, freeSpots, carsInside, address |
| GET | `/parking-lots/{id}` | | one lot plus rows and spots (`occupied` per spot) |
| POST | `/entrance-measurements` | `{"entranceSensorId": 1, "measuredValue": 1}` (1 = in, -1 = out) | entrance/exit sensor |
| POST | `/parking-spot-measurements` | `{"parkingSpotSensorId": 3, "occupied": true}` | spot sensor |
| GET | `/entrance-measurements/all` | | entrance history |
| GET | `/entrance-measurements/sensor/{id}` | | entrance history of one sensor |

Errors: 400 = missing or invalid field, 404 = unknown id.
