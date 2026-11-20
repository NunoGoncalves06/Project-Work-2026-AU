# REST API v1: status and handoff (historical snapshot)

Date: 2026-10-27

> Historical snapshot from 2026-10-27, kept for reference. The code, the README and `docs/admin-dashboard.md` are the current truth; endpoints, tests and deployment have changed since.

## Summary

The REST API v1 is finished and covers everything the first version of the Kotlin app needs. When this was written, the backend tests passed and the full Docker stack had been checked with curl.

The endpoint table is in the [README](../README.md).

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

The sensors do not use the REST API: readings come in over MQTT (topics `carcounter/spots` and `carcounter/entrance`). The payloads are documented in the README. There are no public history endpoints.

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

- **No authentication on MQTT.** Fixed in production (logins, ACL and TLS on port 8883: `mqtt/config/mosquitto.prod.conf`, `aclfile`). The dev `docker-compose.yml` broker is still anonymous.
- **Two test gaps:**
  - No test checks that a spot update also writes its history row.
  - A test that fails halfway can leave changed data behind for the next test.
- **One lot is loaded at a time, with a database query per row.** This is fine for a few lots. It should be optimised if there are ever hundreds of lots.