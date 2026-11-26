# Admin dashboard

A web page for staff to manage the car parks: add and archive lots, rows and spots, attach sensors, run a traffic simulator and update the server. It is served by the backend itself at **https://au-parking.duckdns.org/admin/**. The public app routes (`/parking-lots`, ...) are unchanged and need no login.

Login is Amazon Cognito (hosted login page, authorization code + PKCE). The backend checks every `/api/admin/**` request itself. Hiding a button in the page is only a convenience; the server decides.

## Roles

A user's role is the highest Cognito group they are in: `viewer` < `operator` < `admin`. Other groups are ignored. A user in no role group can sign in but sees "Your account has no role yet" (the API answers 403).

Source: `parking-app/src/main/java/au_project_work/parking_app/security/SecurityConfig.java`.

| Role | Can do |
|---|---|
| viewer | Read only: see the parking lots, rows, parking spots and sensor ids, and the Server update status (all `GET /api/admin/**`). Does not see the Traffic tab. |
| operator | Everything a viewer can, plus the Traffic simulator, switching a parking spot between free and occupied, and the Car in / Car out buttons (`/api/admin/simulator/**`). |
| admin | Everything an operator can, plus adding and archiving parking lots, rows and parking spots, choosing how a parking lot is counted, adding sensors, moving parked sensors, and pressing the Update button. |

## Pages and buttons

The top bar has the tabs Parking lots, Traffic (operator and admin only) and Server update, your name and role, and Log out. A LIVE marker shows the page is receiving changes. Source: `parking-app/src/main/resources/static/admin/index.html` and `app.js`.

### Two ways to count a parking lot

Each parking lot is counted one of two ways, matching the two modes of the sensor board. An admin can switch at any time under **Edit parking lot**; rows and parking spots are kept.

| Method | Sensors | Free parking spots | On the dashboard |
|---|---|---|---|
| **Sensor on every parking spot** (most accurate) | one per parking spot, `carcounter/spots` | parking spots minus occupied sensors | solid label "Sensor on every spot", painted bays showing which spots are free |
| **Entrance and exit sensors** (cheapest) | at the entrance, `carcounter/entrance`, the board sends `dir` +1 for a car in, -1 for a car out | parking spots minus cars inside | dashed label "Entrance and exit sensors", a fill bar with the cars inside; the rows are drawn as layout only, without free/occupied |

A parking lot counted at the entrance still has rows of parking spots: they give its capacity, and they need no sensor. If the lot later gets a sensor on every spot, switch the method and move sensors onto the existing parking spots.

### Parking lots (overview)

1. Shows one ticket per parking lot: street and house number, city, parking lot id, the counting label, "N of M parking spots free", and either the painted bays or the fill bar. A lot with no free parking spot shows FULL.
2. A chip "Server: ..." links to the Server update page.
3. Click a parking lot to open it.
4. **Add a parking lot** (admin only): fill Street, House number, City (default `Aarhus`), pick how cars are counted, press **Add parking lot**.

### Parking lot detail

Shows the free count, the counting label, the cars inside and entrance sensor ids (when the lot has entrance sensors), and one block per row.

- **Sensor on every parking spot**: each row shows its bays. A bay is a painted parking spot: a number when free, a car when occupied. Bays without a sensor are dashed ("no sensor" in the legend). Operators and admins click a bay to mark it occupied or free; bays without a sensor cannot be switched. This sends a simulated reading, not a real one (see "Archiving and simulated readings").
- **Entrance and exit sensors**: a fill bar shows how full the lot is, and each row shows its parking spots as dashed outlines with no state. Operators and admins can press **Car in** / **Car out** to send a simulated entrance reading.

1. **Edit row N** (admin only, opens a panel):
   1. **Add a parking spot**: with a sensor on every spot, type a Sensor id (a whole number, 1 or more) and press **Add parking spot**; the new parking spot gets that sensor. With entrance and exit sensors, just press **Add parking spot** (no sensor).
   2. **Archive a parking spot**: pick it, press **Archive parking spot...**, confirm.
   3. **Archive row N...**: confirm.
2. **Edit parking lot** (admin only, opens a panel):
   1. **How cars are counted**: pick one of the two methods; it is saved at once.
   2. **Add row**: adds an empty row.
   3. **Add an entrance sensor**: type a Sensor id, press **Add entrance sensor**.
   4. **Move a parked sensor here** (sensor on every spot only): a sensor left on an archived parking spot is "parked". Pick the sensor and a parking spot of this lot that has no sensor, press **Move sensor**. If there is no parked sensor, or every parking spot already has one, it says so.
   5. **Archive parking lot...**: confirm; you return to the list.

Every archive asks first: "Archive X?" with **Keep it** (default) and **Archive X**.

### Traffic simulator (operator and admin)

Moves cars in and out of one parking lot until it reaches a target occupancy. Source: `SimulatorService.java`, `Dtos.java`.

1. Pick the Parking lot, set Target occupancy (0 to 100 %, steps of 5, default 70) and Changes per minute (1 to 60, default 12), press **Start**.
2. Only one simulation runs at a time (a second start is refused: "A simulation is already running").
3. While running the page shows Parking lot, Target, Speed, Changes so far and the time left. Press **Stop** to end it.
4. It stops by itself after 60 minutes, and also when the backend shuts down.
5. With a sensor on every parking spot, only parking spots that have a sensor take part; with entrance and exit sensors, the simulator sends Car in / Car out readings until the cars inside reach the target. Each change is a simulated reading. About one change in five moves away from the target, so the lot looks like real traffic.
6. On stop, every parking spot the simulation touched goes back to its last real sensor reading (free if it has none); for a lot counted at the entrance, the run's simulated Car in / Car out readings are removed.

### Server update

Source: `UpdateService.java`, `deploy/au-parking-update.sh`. The page shows the running commit, the latest commit on `main`, the time of the last update and a message. The button is enabled only for an admin, and only in the state Outdated or Error Updating.

| State (button text) | Meaning | Button |
|---|---|---|
| Updated | Server runs the latest `main`. | disabled |
| Outdated | `main` has a newer commit. | enabled (admin) |
| Updating... | A request is pending or an update runs. The page reconnects by itself; it gives up waiting after 20 minutes. | disabled |
| Update Successful | Finished in the last 10 minutes. | disabled |
| Error Updating | The last update failed; the message says why. | enabled (admin), retries |
| Unavailable | No update service on this server (folder `/var/lib/au-parking-update` missing), or status unknown. | disabled |

What the update does, and its limits:

1. At most one update per 5 minutes. A request inside the cooldown is dropped without a message.
2. It first takes a backup, then pulls the image for the new commit. If that image is not built yet (the GitHub workflow is still running), the run waits up to 10 minutes for it, then the status says **New version was not built on GitHub within 10 minutes, nothing changed**. Wait for the workflow and press the button again.
3. If the new version does not become healthy, it goes back to the previous image and commit ("Update failed, rolled back").
4. The dashboard pauses for about a minute while the server restarts.
5. The update page has a log box with the output of the last run (`last-run.log`, `GET /api/admin/update/log`, admin only).

Other status messages come from the script: "Could not fetch the new version, nothing changed", "Backup failed, nothing changed", "Update failed, rolled back", "Update failed, rollback failed".

## Archiving and simulated readings

- **Archive** hides a lot, row or spot from the app and from the dashboard. Nothing is deleted. It cannot be undone from the dashboard.
- The history stays in the database, and the sensor readings of an archived spot are still stored. The forecast ignores archived spots and rows.
- The sensor of an archived spot becomes "parked": it can be moved to another spot (Edit parking lot, Move a parked sensor here).
- A **simulated reading** is a reading created by the dashboard (switching a bay, Car in / Car out, or the traffic simulator). It is stored with a `simulated` mark. It changes what the app shows, but the forecast ignores it.

## Set up Cognito (AWS console)

Do this once, in the AWS region of the server. Console labels change from time to time; the settings below are what matters.

1. Open **Cognito** > **User pools** > **Create user pool**.
2. Application type: **Single-page application (SPA)**, or "Traditional web application" with **no client secret**. Sign-in identifier: **Email**. Self-registration: **off**.
3. Create the **app client**: public, **no client secret**.
4. Under the app client, **Login pages** (hosted UI), set:
   1. Allowed callback URL: `https://au-parking.duckdns.org/admin/`
   2. Allowed sign-out URL: `https://au-parking.duckdns.org/admin/` (the same, including the trailing `/`; the dashboard sends it on log out)
   3. Identity providers: Cognito user pool
   4. OAuth grant type: **Authorization code grant** (the dashboard adds PKCE itself)
   5. OpenID Connect scopes: **openid** only
5. **Domain**: create a Cognito domain with a free prefix, e.g. `au-parking`. The host is then `au-parking.auth.<region>.amazoncognito.com`.
6. In the app client's settings, set **Access token expiration** to a short time, e.g. **60 minutes**. Removing a user from a group only takes effect when their current access token expires, so a long lifetime keeps old rights longer.
7. **Groups**: create `viewer`, `operator` and `admin` (exact names, lower case).
8. **MFA**: set MFA to optional or required with an authenticator app, and enrol every admin. Recommended for admins.
9. **Users** > **Create user**: enter the email, choose an invitation (Cognito emails a temporary password) and create. Open the user > **Add user to group** and pick one group.
10. Note three values: the **Region**, the **User pool ID** (`<region>_xxxxxxxxx`) and the app client's **Client ID**. Together with the domain host they are the four `.env` values below.

### Same, with the AWS CLI

The repo has no Cognito template, so this is the CLI route (not run against this project, check the output of each command). Run in CloudShell or a PC logged in to AWS. One command at a time.

```bash
REGION=<region>
PREFIX=au-parking          # must be free in the region
URL=https://au-parking.duckdns.org/admin/

POOL=$(aws cognito-idp create-user-pool --region $REGION --pool-name au-parking-admin \
  --username-attributes email --auto-verified-attributes email \
  --admin-create-user-config AllowAdminCreateUserOnly=true \
  --policies 'PasswordPolicy={MinimumLength=12,RequireUppercase=true,RequireLowercase=true,RequireNumbers=true,RequireSymbols=false}' \
  --query UserPool.Id --output text)
echo $POOL

CLIENT=$(aws cognito-idp create-user-pool-client --region $REGION --user-pool-id $POOL --client-name au-parking-admin \
  --no-generate-secret --supported-identity-providers COGNITO \
  --allowed-o-auth-flows code --allowed-o-auth-flows-user-pool-client --allowed-o-auth-scopes openid \
  --callback-urls $URL --logout-urls $URL \
  --access-token-validity 60 --token-validity-units AccessToken=minutes \
  --query UserPoolClient.ClientId --output text)
echo $CLIENT

aws cognito-idp create-user-pool-domain --region $REGION --user-pool-id $POOL --domain $PREFIX

for g in viewer operator admin; do
  aws cognito-idp create-group --region $REGION --user-pool-id $POOL --group-name $g
done
```

Turn on MFA with an authenticator app (admins enrol on their first login):

```bash
aws cognito-idp set-user-pool-mfa-config --region $REGION --user-pool-id $POOL \
  --software-token-mfa-configuration Enabled=true --mfa-configuration OPTIONAL
```

Create a user (Cognito emails a temporary password) and add them to a group:

```bash
U=name@example.com
aws cognito-idp admin-create-user --region $REGION --user-pool-id $POOL --username "$U" \
  --user-attributes Name=email,Value="$U" Name=email_verified,Value=true
aws cognito-idp admin-add-user-to-group --region $REGION --user-pool-id $POOL --username "$U" --group-name viewer
```

To change a role later: `admin-remove-user-from-group`, then `admin-add-user-to-group`. The change reaches the user when their access token expires (see step 6 above) or when they sign in again.

### Put the values on the server

On the server, add four lines to `~/au-parking/.env` (names only here; never commit `.env`). `COGNITO_DOMAIN` is the host only, without `https://`.

```bash
COGNITO_REGION=<region>
COGNITO_USER_POOL_ID=<region>_xxxxxxxxx
COGNITO_CLIENT_ID=<client id>
COGNITO_DOMAIN=au-parking.auth.<region>.amazoncognito.com
```

Set all four or none. With only some of them the backend refuses to start. With none, every `/api/admin` request is refused (503) and the page says "Admin login is not set up". Then recreate the containers:

```bash
cd ~/au-parking
docker compose -f docker-compose.prod.yml up -d
```

Open `https://au-parking.duckdns.org/admin/` and sign in. Test once with a user of each role.

## Local development

Without Cognito, run the stack with the login switched off:

```bash
ADMIN_AUTH=off docker compose up -d --build
```

Then open http://localhost:8080/admin/. Everyone counts as admin and there is no Log out button.

Warning: `ADMIN_AUTH=off` opens the whole admin API to anyone who can reach the port, including other machines on your network. Never use it on the server. `docker-compose.prod.yml` does not pass it on, and the backend refuses to start if it is set together with the four `COGNITO_*` values.

## Audit log

Every change made through the admin API (create, archive, move sensor, simulator, update request) is logged by the logger `audit`, with the Cognito `sub` and username. Example format: `archive lot by sub=<sub> username=<name> lot=3`.

```bash
docker compose -f docker-compose.prod.yml logs parking-app | grep audit
```

Docker keeps these logs only as long as the container exists; after `up -d` recreates it, older lines are gone.

Related: `docs/deploy-aws.md` (server setup, update system, first deploy checklist).
