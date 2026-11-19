# Plan: AU Parking admin dashboard (v2, after review)

Date: 2026-11-18. Auth pattern: the Cognito hosted login with PKCE and roles from groups (docs/admin-dashboard.md), ported to Spring Boot 4.1.1.
Load assumed: one t3.micro server, a few admins, tens of spots.

## Decisions
- Roles: `viewer` (read), `operator` (simulators), `admin` (lots/spots, update button). Enforced on the server per route.
- Removing a lot/row/spot archives it (`archived_at`); history stays for the forecast. A spot with no history may be hard-deleted.
- A physical sensor is moved to another spot (update its `parking_spot_id`), never re-created: its id is the `device_id` the board sends.
- Simulated readings use the same `record()` path as sensors, marked `simulated = true`; the forecast ignores them.
- Login fails CLOSED: without the three `COGNITO_*` values every `/api/admin/**` request is refused. Only an explicit `ADMIN_AUTH=off` (dev compose only, never prod) disables it.
- Database migrations are additive only (add columns/tables/indexes; never rename or drop), so rolling back to the previous image stays possible.
- Dashboard: plain HTML/JS at `/admin/`, same origin as the API, styled after the app (`android-app/.../ui/theme/Theme.kt`, `ui/Ticket.kt`), designed with `/impeccable` and `frontend-design`.

## Step 1: Add Flyway migrations for the new schema
Add `spring-boot-starter-flyway` and `flyway-database-postgresql` (Boot 4 needs the starter; plain `flyway-core` runs nothing). `V1` = the current `db/init/01_create.sql` schema, adopted on the live database with `baseline-on-migrate`. `V2` (additive): identity ids for lots/rows/spots starting after the current max, `archived_at` on lots/rows/spots, `simulated` on `parking_spot_measurements`. Update the lot list/detail, fingerprint and forecast queries to skip archived items and simulated readings.
Acceptance: a test asserts `flyway_schema_history` contains V2 on a fresh DB (db/init then Flyway) and on a baselined one; archived spots disappear from `GET /parking-lots`; forecast ignores simulated rows.
Out of scope: dropping or renaming any existing column.

## Step 2: Add Cognito login for the admin API (fail closed)
Add `spring-boot-starter-oauth2-resource-server`. Validate RS256 signature, issuer, `exp`, `token_use=access`, `client_id`; role = highest of `cognito:groups` on the ladder. Public: existing app routes, `/api/health`, `/api/auth/config`; `/api/admin/**` needs the route's minimum role. Stateless, CSRF off. 401 with `WWW-Authenticate: Bearer`, 403 for a low role, 503 if the JWKS is unreachable. Missing config refuses admin routes unless `ADMIN_AUTH=off`; partial config fails start-up.
Acceptance: role-matrix test (every admin route x every role, using spring-security-test); unit tests for groups-to-role and the client_id check; no config means 401/503 on admin routes, never open.
Out of scope: login for the Android app.

## Step 3: Add lot, row, spot and sensor management to the admin API
Admin routes to create lots (address, coordinates), rows, spots with their sensor, and entrance sensors; archive lots/rows/spots; move a sensor to another spot. One transaction per change; validated input; 409 when a sensor id is in use by an active spot; an audit log line per change (who: `sub`/email, what, when).
Acceptance: MockMvc tests for create, archive, move sensor and 409; the app's live stream reports the change.
Out of scope: bulk import.

## Step 4: Add the spot and traffic simulators
Operator routes: set one spot free/taken (same `record()` as sensors, `simulated = true`); start/stop/status of a traffic simulation for one lot (target occupancy, changes per minute capped at 60), one at a time, auto-stops after 60 minutes. On stop, each touched spot returns to its last real reading.
Acceptance: unit test with a seeded random source; tests for the 60-minute auto-stop, single instance, and restore on stop.

## Step 5: Add the one-click server update system
GitHub Actions builds the backend image on push to `main` and pushes `ghcr.io/...:latest` and `:<sha>`. `docker-compose.prod.yml` gets `name: au-parking` (volumes keep their names whatever the folder) and uses the GHCR image. `deploy/au-parking-update.sh` (root, fixed, no input): flock; `backup-db.sh`; git fetch/reset to `origin/main` run as `ec2-user`; re-apply `aclfile` (1883, 600) and `chmod a+rX db`; reinstall `/usr/local/sbin` scripts and systemd units then `daemon-reload`; `compose pull` + `up -d`; `caddy reload`; health check; on failure roll back to the previous image tag; write a status JSON. A `.path` unit reacts to the request file written by the backend into one shared folder; a 5-minute timer records whether the checkout is behind. Backend exposes the states Updated / Outdated / Updating... / Error Updating / Update Successful and never gets Docker access.
Acceptance: self-check with fake `git`/`docker` for success, failure with rollback, concurrent requests, and already up to date; the backend can only write the request file.
Out of scope: database rollbacks (migrations are additive).

## Step 6: Add the admin dashboard frontend
Plain HTML/JS at `/admin/`, design from the app's ticket look (palette, typewriter font, spot strip, LIVE badge, printed stamp) using `/impeccable` and `frontend-design`. Pages: overview (live via the public stream with `EventSource`), lot detail (click a spot to toggle; add/archive/move sensor), traffic simulator, server update button. PKCE login, tokens in `sessionStorage`, refresh before expiry, 401 sends to login. No `innerHTML` with API data; role-based hiding is cosmetic only.
Acceptance: manual walkthrough per role; works at phone width; visible keyboard focus.

## Step 7: Add security headers for the dashboard
In the Caddyfile for `/admin`: CSP `default-src 'self'; script-src 'self'; connect-src 'self' <cognito domain>; frame-ancestors 'none'`, `Referrer-Policy`, `X-Content-Type-Options`. No Caddy rate limiting (not in the stock image).
Acceptance: response headers present on `/admin/`; dashboard still logs in and loads with the CSP active.

## Step 8: Review the security-critical steps
Code review and security review of Steps 2, 3 and 5 by a different model than the writer; fix findings.

## Step 9: Update the docs
`docs/admin-dashboard.md` (users, roles, buttons) and `docs/deploy-aws.md` (update system, new `.env` values, conversion of `~/au-parking` to a git checkout).

## Manual: AWS and GitHub (done by hand, in order)
1. Before anything: `pg_dump --schema-only` on the server and compare with V1; take a `backup-db` backup.
2. GitHub: protect `main`; after the first workflow run, make the GHCR package public (no secrets in it).
3. CloudShell (instance's region): run `deploy/cognito.sh` with callback `https://au-parking.duckdns.org/admin/`; create users with the CLI block in docs/admin-dashboard.md; turn on MFA for admins.
4. Server: read-only deploy key; convert `~/au-parking` to a git checkout in the same folder, keeping `.env` and `pwfile`; add the `COGNITO_*` lines; install the update units.
5. First run: `up -d`, log in at `/admin/` with each role, then press Update.
