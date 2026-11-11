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
| GET | `/parking-spot-measurements/all` | | spot sensor history |
| GET | `/entrance-measurements/all` | | entrance history |
| GET | `/entrance-measurements/sensor/{id}` | | entrance history of one sensor |
| GET | `/parking-lots/stream` | | server-sent events: `changed` when the data changed, `keep-alive` otherwise. Carries no data; the app refetches the GETs above when it arrives |

Watch the stream from a terminal with `curl -N localhost:8080/parking-lots/stream`.

Sensor readings come in only over MQTT (broker: the `mosquitto` container), not through the REST API:

| Topic | Payload |
|---|---|
| `carcounter/spots` | `{"device_id": 3, "occupied": true}` (`device_id` = parking spot sensor id) |
| `carcounter/entrance` | `{"device_id": 1, "measured_value": 1}` (1 = in, -1 = out) |

Fake a sensor reading while the stack runs: `docker compose exec mosquitto mosquitto_pub -t carcounter/spots -m '{"device_id": 16, "occupied": true}'`. A message with a missing field or an unknown sensor is logged and skipped.

**How the app stays up to date.** It keeps one `/parking-lots/stream` connection open and refetches when the server says `changed`, so an update shows up within a few seconds without the app asking over and over. The server notices a change by re-reading a small fingerprint of the data every 2 s, which catches MQTT readings and edits made straight in the database alike. That check runs once for the whole server, not once per app, and only while at least one app is connected. Each app still refetches on its own every 60 s as a backstop, dropping to 5 s whenever the stream is down.

Errors: 404 = unknown id.

Android app (`android-app/`):

- Requirements: Android Studio (or the Android SDK at `~/Android/Sdk`) and the backend running via `docker compose up`
- Open the `android-app/` folder in Android Studio and run the `app` configuration on an emulator
- Or from the command line: `cd android-app && ./gradlew :app:installDebug` with an emulator running
- **On first launch, tap "Allow"** on the "nearby devices" dialog. Android 17 blocks apps from reaching local-network addresses such as the dev backend unless they have this permission, which only debug builds ask for. If you tapped "Don't allow", the app shows "Can't reach server". To fix it, re-enable the permission in the app's settings, or reinstall.
- The debug app calls `http://localhost:8080/` on the phone/emulator. Forward that to your laptop with `adb reverse tcp:8080 tcp:8080` (works for emulator, USB and wireless debugging)
  - the forward is lost every time the device reconnects or adb restarts: re-run the command then
- **Backend on a teammate's machine instead?** Point the app straight at it, no `adb reverse` needed. Both machines have to be on the same wifi, and the teammate has to run `docker compose up`:

  ```
  cd android-app && ./gradlew :app:installDebug -PapiBaseUrl=http://192.168.178.20:8080/
  ```

  Replace the IP with the one of the machine running Docker. To not retype it every time, put `apiBaseUrl=http://192.168.178.20:8080/` in your own `~/.gradle/gradle.properties` (never committed). Debug builds allow plain http to any host for exactly this; release builds still refuse cleartext.
- App says "Can't reach the server"? Check in this order:
  1. backend up: `curl localhost:8080/parking-lots` (if not: `docker compose up -d`)
  2. forward there: `adb reverse --list` (if empty: `adb reverse tcp:8080 tcp:8080`)
- Always run the **debug** build. Release builds are not configured yet: there is no real server URL and no signing
- Low on RAM? Start the emulator with `-memory 1536 -gpu swiftshader_indirect` and stop the Gradle daemons first (`./gradlew --stop`)
- Unit tests: `cd android-app && ./gradlew :app:testDebugUnitTest`
