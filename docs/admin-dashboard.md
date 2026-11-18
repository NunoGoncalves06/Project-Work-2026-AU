# Admin dashboard

A web page for staff to manage the car parks: add and archive lots, rows and spots, attach sensors, run a traffic simulator and update the server. It is served by the backend itself at **https://au-parking.duckdns.org/admin/**. The public app routes (`/parking-lots`, ...) are unchanged and need no login.

Login is Amazon Cognito (hosted login page, authorization code + PKCE). The backend checks every `/api/admin/**` request itself. Hiding a button in the page is only a convenience; the server decides.

## Roles

A user's role is the highest Cognito group they are in: `viewer` < `operator` < `admin`. Other groups are ignored. A user in no role group can sign in but sees "Your account has no role yet" (the API answers 403).

Source: `parking-app/src/main/java/au_project_work/parking_app/security/SecurityConfig.java`.

| Role | Can do |
|---|---|
| viewer | Read only: see the lots, rows, spots and sensor ids, and the Server update status (all `GET /api/admin/**`). Does not see the Traffic tab. |
| operator | Everything a viewer can, plus the Traffic simulator and flipping a spot between free and taken (`/api/admin/simulator/**`). |
| admin | Everything an operator can, plus adding and archiving lots, rows and spots, adding sensors, moving parked sensors, and pressing the Update button. |

## Pages and buttons

The top bar has the tabs Lots, Traffic (operator and admin only) and Server update, your name and role, and Log out. A LIVE marker shows the page is receiving changes. Source: `parking-app/src/main/resources/static/admin/index.html` and `app.js`.

### Lots (overview)

1. Shows one ticket per lot: street and house number, city, lot id, "N of M free" and the painted bays. A lot with no free spot shows FULL.
2. A chip "Server: ..." links to the Server update page.
3. Click a lot to open it.
4. **Add a lot** (admin only): fill Street, House number, City (default `Aarhus`) and press **Add lot**.

### Lot detail

Shows the free count, the entrance sensor ids and one block per row with its bays. A bay is a painted space: a number when free, a car when taken. Bays without a sensor are marked "no sensor" in the legend.

1. **Flip a spot** (operator and admin): click a bay to mark it taken or free. Bays without a sensor cannot be flipped. This sends a simulated reading, not a real one (see "Archiving and simulated readings").
2. **Edit row N** (admin only, opens a panel):
   1. **Add spot**: type a Sensor id (a whole number, 1 or more) and press **Add spot**. The new spot gets that sensor.
   2. **Archive a spot**: pick the spot, press **Archive spot...**, confirm.
   3. **Archive row N...**: confirm.
3. **Edit lot** (admin only, opens a panel):
   1. **Add row**: adds an empty row.
   2. **Add an entrance sensor**: type a Sensor id, press **Add entrance sensor**.
   3. **Move a parked sensor here**: a sensor left on an archived spot is "parked". Pick the sensor and a spot of this lot that has no sensor, press **Move sensor**. If there is no parked sensor, or every spot already has one, it says so.
   4. **Archive lot...**: confirm; you return to the lot list.

Every archive asks first: "Archive X?" with **Keep it** (default) and **Archive X**.

### Traffic simulator (operator and admin)

Moves cars in and out of one lot until it reaches a target occupancy. Source: `SimulatorService.java`, `Dtos.java`.

1. Pick the Lot, set Target occupancy (0 to 100 %, steps of 5, default 70) and Changes per minute (1 to 60, default 12), press **Start**.
2. Only one simulation runs at a time (a second start is refused: "A simulation is already running").
3. While running the page shows Lot, Target, Speed, Changes so far and the time left. Press **Stop** to end it.
4. It stops by itself after 60 minutes, and also when the backend shuts down.
5. Only spots that have a sensor take part. Each change is a simulated reading. About one change in five moves away from the target, so the lot looks like real traffic.
6. On stop, every spot the simulation touched goes back to its last real sensor reading (free if it has none).

### Server update

Source: `UpdateService.java`, `deploy/au-parking-update.sh`. The page shows the running commit, the latest commit on `main`, the time of the last update and a message. The button is enabled only for an admin, and only in the state Outdated or Error Updating.

| State (button text) | Meaning | Button |
|---|---|---|
| Updated | Server runs the latest `main`. | disabled |
| Outdated | `main` has a newer commit. | enabled (admin) |
| Updating... | A request is pending or an update runs. The page reconnects by itself; it gives up waiting after 15 minutes. | disabled |
| Update Successful | Finished in the last 10 minutes. | disabled |
| Error Updating | The last update failed; the message says why. | enabled (admin), retries |
| Unavailable | No update service on this server (folder `/var/lib/au-parking-update` missing), or status unknown. | disabled |

What the update does, and its limits:

1. At most one update per 5 minutes. A request inside the cooldown is dropped without a message.
2. It first takes a backup, then pulls the image for the new commit. If that image is not built yet (the GitHub workflow is still running), the status says **New version not built yet, nothing changed**. Wait for the workflow and press the button again.
3. If the new version does not become healthy, it goes back to the previous image and commit ("Update failed, rolled back").
4. The dashboard pauses for about a minute while the server restarts.

Other status messages come from the script: "Could not fetch the new version, nothing changed", "Backup failed, nothing changed", "Update failed, rolled back", "Update failed, rollback failed".

## Archiving and simulated readings

- **Archive** hides a lot, row or spot from the app and from the dashboard. Nothing is deleted. It cannot be undone from the dashboard.
- The history stays in the database, and the sensor readings of an archived spot are still stored. The forecast still uses that history.
- The sensor of an archived spot becomes "parked": it can be moved to another spot (Edit lot, Move a parked sensor here).
- A **simulated reading** is a reading created by the dashboard (flipping a bay, or the traffic simulator). It is stored with a `simulated` mark. It changes what the app shows, but the forecast ignores it.

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

The repo has no Cognito template, so this is the CLI route (adapted from `HANDOFF.md` section 7; not run against this project, check the output of each command). Run in CloudShell or a PC logged in to AWS. One command at a time.

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
