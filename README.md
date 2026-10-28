# Project-Work-2026-AU


Run application:

- Requirements: Docker with Compose
- Start application (parking-app container and db container) with: `docker compose up`
- if you want to (re)build the image use: `docker compose up --build`
- application is then available under http://localhost:8080
- Stop application with: `docker compose down`
- The DB init scripts only run on an empty volume. After changing `db/init`, reset with: `docker compose down -v` (deletes DB data)
- To test db initialization run select_checks SQL File in running container with: `docker compose exec db sh -c 'psql -U "$POSTGRES_USER" -d "$POSTGRES_DB" -f /scripts/select_checks.sql'`
- Want more lots to test the app with? Load fake demo lots (3 extra lots, one of them full) into the running DB: `docker compose exec db sh -c 'psql -U "$POSTGRES_USER" -d "$POSTGRES_DB" -f /scripts/demo_lots.sql'`. A DB reset removes them
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

Android app (`android-app/`):

- Requirements: Android Studio (or the Android SDK at `~/Android/Sdk`) and the backend running via `docker compose up`
- Open the `android-app/` folder in Android Studio and run the `app` configuration on an emulator
- Or from the command line: `cd android-app && ./gradlew :app:installDebug` with an emulator running
- **On first launch, tap "Allow"** on the "nearby devices" dialog. Android 17 blocks apps from reaching local-network addresses such as the dev backend unless they have this permission, which only debug builds ask for. If you tapped "Don't allow", the app shows "Can't reach server". To fix it, re-enable the permission in the app's settings, or reinstall.
- The debug app calls `http://localhost:8080/` on the phone/emulator. Forward that to your laptop with `adb reverse tcp:8080 tcp:8080` (works for emulator, USB and wireless debugging)
  - the forward is lost every time the device reconnects or adb restarts: re-run the command then
- App says "Can't reach the server"? Check in this order:
  1. backend up: `curl localhost:8080/parking-lots` (if not: `docker compose up -d`)
  2. forward there: `adb reverse --list` (if empty: `adb reverse tcp:8080 tcp:8080`)
- Always run the **debug** build. Release builds are not configured yet: there is no real server URL and no signing
- Low on RAM? Start the emulator with `-memory 1536 -gpu swiftshader_indirect` and stop the Gradle daemons first (`./gradlew --stop`)
- Unit tests: `cd android-app && ./gradlew :app:testDebugUnitTest`
