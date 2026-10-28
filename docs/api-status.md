# REST API v1: status and handoff

Date: 2026-10-27

## Summary

The REST API v1 is finished and covers everything the first version of the Kotlin app needs. All 9 backend tests pass, and the full Docker stack has been started and checked with curl.

Full design: [`docs/superpowers/specs/2026-10-26-rest-api-design.md`](superpowers/specs/2026-10-26-rest-api-design.md). The endpoint table is in the [README](../README.md).

## What the app can use

| Endpoint | Gives the app |
|---|---|
| `GET /parking-lots` | The list of lots: address (for the "Open in Google Maps" button), `totalSpots`, `freeSpots`, `carsInside` |
| `GET /parking-lots/{id}` | One lot with its rows and every spot's `occupied` state (404 if the lot doesn't exist) |

Example response from `GET /parking-lots`:

```json
[{ "id": 1,
   "address": { "street": "Finlandsgade", "houseNr": "22", "city": "Aarhus", "latitude": 56.1716, "longitude": 10.1897 },
   "totalSpots": 16, "freeSpots": 12, "carsInside": 4 }]
```

The sensors write to `POST /parking-spot-measurements` and `POST /entrance-measurements`. The request bodies are documented in the README.

## Where the code lives

```
parking-app/src/main/java/au_project_work/parking_app/
├── ParkingAppApplication.java   starts the server
├── api/          REST controllers + Dtos.java (JSON shapes)
├── model/        JPA entities, one per DB table
└── repository/   database queries
```

## For everyone after pulling

1. **Reset your local database once:**

   ```
   docker compose down -v
   docker compose up --build
   ```

   The schema changed, so the DB init scripts in `db/init` have to run again. They only run on an empty volume, so this deletes your local DB data.

2. **JSON keys on `/entrance-measurements` are now camelCase.** For example, `entranceSensorID` became `entranceSensorId`. `measuredAt` is now an ISO timestamp with a time zone. Update any scripts that read these keys.

3. **The schema changed in `db/init`.**
   - Each parking spot now has its own sensor, using the new tables `parking_spots`, `parking_spot_sensors` and `parking_spot_measurements`.
   - The per-row sensor tables are removed.
   - Free spots are now counted from the spot sensors.

   Lena, please review this change.

4. **Run the backend tests with** `cd parking-app && ./gradlew test`. They need Docker running.

## Fixing "address already in use" on port 5432

This only affects you if PostgreSQL is installed directly on your machine (outside Docker).

**What happens.** Every network program listens on a port. A port can only be used by one program at a time.

- `docker-compose.yml` makes the database container available on your machine at port 5432, through the line `ports: "5432:5432"`.
- A locally installed PostgreSQL also listens on 5432 by default.
- If it is running, Docker can't take the port, and `docker compose up` fails with:

  ```
  Bind for 0.0.0.0:5432 failed: port is already allocated / address already in use
  ```

**Check whether you have it:**

```
ss -ltn | grep 5432
systemctl status postgresql
```

**Fix, option A: stop the local PostgreSQL** (recommended if you don't use it for anything else):

```
sudo systemctl stop postgresql          # stop it now
sudo systemctl disable postgresql       # optional: don't start it at boot
```

To start it again later, run `sudo systemctl start postgresql`.

**Fix, option B: give the Docker database another host port.** In `docker-compose.yml`, change the db service's port mapping:

```yaml
    ports:
      - "5433:5432"
```

- The app container still reaches the DB at `db:5432` inside Docker, so nothing else in compose changes.
- If you run the Spring app outside Docker (from the IDE or `./gradlew bootRun`), it reads `parking-app/src/main/resources/application.properties`, which points to `localhost:5432`. Change it to `localhost:5433`.
- Don't commit this change, because it's specific to your machine.

## Known gaps (accepted for v1)

- **No authentication on the sensor POST endpoints.** Anyone who can reach the server can send measurements. That's fine for the demo, but it needs a fix before real deployment.
- **Two test gaps:**
  - No test checks that a spot update also writes its history row.
  - A test that fails halfway can leave changed data behind for the next test.
- **One lot is loaded at a time, with a database query per row.** This is fine for a few lots. It should be optimised if there are ever hundreds of lots.