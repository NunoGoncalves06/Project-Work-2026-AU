# Deploying to AWS

The whole stack (server, Postgres, Mosquitto) runs on one EC2 instance with Docker Compose, behind Caddy for HTTPS. Files: `docker-compose.prod.yml`, `Caddyfile`, `mqtt/config/mosquitto.prod.conf`, `mqtt/config/aclfile`, `deploy/`, `.env.example`. The local `docker-compose.yml` is unchanged and still what you use on your laptop.

## The instance

- Amazon Linux 2023, **64-bit (x86)**, t3.micro, 20 GiB gp3, credit specification **Standard**.
- Security group inbound: **22 from your IP only**, **80 and 443 from anywhere**, **8883 from anywhere** (encrypted MQTT for the sensors, see below). Do not open 5432, 8080 or 1883. Port 1883 (plain MQTT) is only for the backend inside Docker and is never published.
- An **Elastic IP** attached, so the address survives a stop/start.
- User data installs Docker, the Compose plugin and 2 GB of swap on first boot. Check with `docker compose version` and `free -h`.

## One-time setup

1. **A hostname for the Elastic IP.** HTTPS needs a name, not a bare IP. Free option: sign in at duckdns.org, create e.g. `au-parking`, set its IP to the Elastic IP. Result: `au-parking.duckdns.org`.
2. **`.env`** with the passwords is created on the server itself (first deploy, below), so the passwords never sit on a laptop. Keep a copy of it in a password manager: it is the only copy.

## Deploy (from the repo root, in PowerShell)

```powershell
$key = "$HOME\OneDrive\Desktop\AWS Keys\Project Work Aarhus 2026_2027 Key Pair.pem"
$srv = "ec2-user@<ELASTIC-IP>"

# build on the laptop: the server has too little memory to run the Gradle build
docker build -t parking-app:prod parking-app
docker save -o parking-app.tar parking-app:prod   # a file, not a pipe: PowerShell pipes corrupt binary data

ssh -i $key $srv "mkdir -p au-parking/db au-parking/mqtt/config au-parking/deploy"
scp -i $key docker-compose.prod.yml Caddyfile parking-app.tar "${srv}:au-parking/"
scp -i $key -r db/init "${srv}:au-parking/db/"
scp -i $key mqtt/config/mosquitto.prod.conf "${srv}:au-parking/mqtt/config/"
scp -i $key mqtt/config/aclfile "${srv}:au-parking/"   # staged: the copy in mqtt/config belongs to the broker and ec2-user can't overwrite it
scp -i $key deploy/sync-mqtt-cert.sh deploy/sync-mqtt-cert.service deploy/sync-mqtt-cert.timer deploy/backup-db.sh deploy/backup-db.service deploy/backup-db.timer "${srv}:au-parking/deploy/"
Remove-Item parking-app.tar
```

The compose file now runs `au-parking-backend:current`, filled from ghcr.io (see "One-click update"); to run the laptop-built image above instead, tag it `docker tag parking-app:prod au-parking-backend:current`. Always use `docker-compose.prod.yml` on the server. Never run the plain `docker compose up` (the dev `docker-compose.yml`): it publishes an anonymous broker on port 1883.

Then on the server (`ssh -i $key $srv`):

```bash
cd au-parking
docker load -i parking-app.tar && rm parking-app.tar
# scp from Windows leaves the files readable only by ec2-user; Postgres and Mosquitto run as
# other users inside their containers, and Postgres exits with code 2 if it can't list db/init
chmod -R a+rX db && chmod a+r mqtt/config/mosquitto.prod.conf Caddyfile
# the broker (uid 1883) must own the login and ACL files (Mosquitto 2.1 warns otherwise, later versions refuse)
sudo install -o 1883 -g 1883 -m 600 aclfile mqtt/config/aclfile && rm aclfile
```

**First deploy only:** create `.env` with random passwords (change `DOMAIN` to your hostname), then print it once and save it in a password manager.

```bash
cat > .env <<EOF
DOMAIN=au-parking.duckdns.org
POSTGRES_PASSWORD=$(tr -dc 'a-f0-9' < /dev/urandom | head -c 48)
APP_DB_PASSWORD=$(tr -dc 'a-f0-9' < /dev/urandom | head -c 48)
MQTT_USERNAME=parking-backend
MQTT_PASSWORD=$(tr -dc 'a-f0-9' < /dev/urandom | head -c 48)
SENSOR_PASSWORD=$(tr -dc 'a-f0-9' < /dev/urandom | head -c 48)
EOF
chmod 600 .env && cat .env
```

Then create the broker users from the passwords in `.env`: `parking-backend` for the server itself, `sensor` for the sensors.

```bash
set -a; source .env; set +a
docker run --rm -v "$PWD/mqtt/config:/c" eclipse-mosquitto:2 mosquitto_passwd -c -b /c/pwfile "$MQTT_USERNAME" "$MQTT_PASSWORD"
docker run --rm -v "$PWD/mqtt/config:/c" eclipse-mosquitto:2 mosquitto_passwd -b /c/pwfile sensor "$SENSOR_PASSWORD"
sudo chown 1883:1883 mqtt/config/pwfile && sudo chmod 600 mqtt/config/pwfile
```

Take a backup (`sudo systemctl start backup-db.service`) right before deploying the Flyway version, and read "Before the first Flyway deploy" below.

### `.env` values

Source: `.env.example` (copy it, never commit `.env`). `docker-compose.prod.yml` stops with a message if a required value is missing.

| Variable | Required | Meaning |
|---|---|---|
| `DOMAIN` | yes | Hostname of the Elastic IP; Caddy gets its certificate for it. |
| `POSTGRES_PASSWORD` | yes | Owner login of the database (also used by the Flyway migrations). Postgres only reads it while its volume is empty. |
| `APP_DB_PASSWORD` | yes | The backend's own database login `parking_app` (rows only, no schema changes). Set again on every start, so changing it here and restarting changes it. 16 or more letters and digits only, e.g. `openssl rand -hex 24`. |
| `COGNITO_REGION`, `COGNITO_USER_POOL_ID`, `COGNITO_CLIENT_ID`, `COGNITO_DOMAIN` | all four or none | Admin dashboard login, see `docs/admin-dashboard.md`. `COGNITO_DOMAIN` is the host only, without `https://`. With none, the admin API refuses everything (503). With only some, the backend does not start. |
| `MQTT_CLIENT_ID` | optional | The backend's MQTT client id. Set it to a value that is not public (not the default name); unset = random per start, so queued sensor messages do not survive a restart. |
| `MQTT_USERNAME`, `MQTT_PASSWORD` | yes | The backend's broker login; must also be a user in `mqtt/config/pwfile`. |
| `SENSOR_PASSWORD` | when creating `pwfile` | The sensors' login. Not read by compose. |
| `ADMIN_AUTH` | never on the server | `off` removes the login completely. For local development only. `docker-compose.prod.yml` does not pass it on. |

An existing `.env` from before the dashboard has no `APP_DB_PASSWORD`. Add it:

```bash
echo "APP_DB_PASSWORD=$(openssl rand -hex 24)" >> .env
```

### Start

Start (and, on later deploys, restart with the new image). On a **brand-new server** read "Encrypted MQTT for sensors" below first: Mosquitto cannot start until its certificate exists.

```bash
docker compose -f docker-compose.prod.yml up -d
docker compose -f docker-compose.prod.yml logs -f parking-app   # Ctrl+C to stop following
```

## Check it works

From the laptop:

```bash
curl https://<DOMAIN>/parking-lots           # JSON with the car parks
curl -N https://<DOMAIN>/parking-lots/stream  # "hello", then "keep-alive" every ~30 s
```

On the server, publish a reading (this uses the internal plain listener) and watch the stream say `changed`:

```bash
docker compose -f docker-compose.prod.yml exec mosquitto mosquitto_pub -u sensor -P '<sensor password>' \
  -t carcounter/spots -m '{"device_id":9,"occupied":true}'
```

The phone app talks to `https://au-parking.duckdns.org/` by default (`android-app/app/build.gradle.kts`), so a plain build uses the server, no `adb reverse` needed. If `DOMAIN` ever changes, change that default too.

```bash
cd android-app && ./gradlew :app:installDebug
```

## Encrypted MQTT for sensors (port 8883)

Sensors connect to `mqtts://au-parking.duckdns.org:8883` (details for the firmware team: `docs/sensor-connection.md`). Mosquitto uses the same Let's Encrypt certificate that Caddy gets for the site. A small script copies it from Caddy's volume to `/etc/au-parking/mqtt-certs` (root-owned, outside the repo) and restarts Mosquitto when it changes. An hourly systemd timer runs the script.

### 1. Security group

In the EC2 security group add inbound **Custom TCP, port 8883, source Anywhere-IPv4**. **Never open 1883.**

### 2. Copy the files

The deploy commands above already copy `deploy/`, `mqtt/config/mosquitto.prod.conf`, `mqtt/config/aclfile` and `docker-compose.prod.yml`. If the server was deployed before, run just these from the repo root (PowerShell):

```powershell
ssh -i $key $srv "mkdir -p au-parking/deploy"
scp -i $key docker-compose.prod.yml "${srv}:au-parking/"
scp -i $key mqtt/config/mosquitto.prod.conf "${srv}:au-parking/mqtt/config/"
scp -i $key mqtt/config/aclfile "${srv}:au-parking/"   # staged: the copy in mqtt/config belongs to the broker and ec2-user can't overwrite it
scp -i $key deploy/sync-mqtt-cert.sh deploy/sync-mqtt-cert.service deploy/sync-mqtt-cert.timer deploy/backup-db.sh deploy/backup-db.service deploy/backup-db.timer "${srv}:au-parking/deploy/"
```

On the server, from `~/au-parking`:

```bash
chmod a+r mqtt/config/mosquitto.prod.conf
sudo install -o 1883 -g 1883 -m 600 aclfile mqtt/config/aclfile && rm aclfile
sudo chown 1883:1883 mqtt/config/pwfile && sudo chmod 600 mqtt/config/pwfile
```

### 3. Install the script and the units

The script is installed as a root-owned copy, so `ec2-user` cannot edit what root runs. The unit files are copied, not linked.

```bash
sudo dnf install -y openssl    # the script needs it; harmless if already installed
sudo install -o root -g root -m 755 deploy/sync-mqtt-cert.sh /usr/local/sbin/sync-mqtt-cert.sh
sudo cp deploy/sync-mqtt-cert.service deploy/sync-mqtt-cert.timer /etc/systemd/system/
sudo systemctl daemon-reload
```

The service runs `/usr/local/sbin/sync-mqtt-cert.sh` in `/home/ec2-user/au-parking`. After you change `deploy/sync-mqtt-cert.sh`, install it again with the `install` line.

### 4. Start it, in the right order

If Mosquitto cannot load its certificate (missing, or key does not match), it does not start at all. That also takes down the internal 1883 the backend uses. So the order matters.

**Existing server (Caddy already has its certificate).**

```bash
sudo systemctl start sync-mqtt-cert.service
sudo ls -l /etc/au-parking/mqtt-certs     # server.crt 644 and server.key 600, both owned by 1883
docker compose -f docker-compose.prod.yml up -d
sudo systemctl enable --now sync-mqtt-cert.timer
```

**Fresh server (Caddy has no certificate yet).**

```bash
docker compose -f docker-compose.prod.yml up -d
docker compose -f docker-compose.prod.yml logs caddy    # wait for "certificate obtained successfully"
sudo systemctl start sync-mqtt-cert.service
sudo systemctl enable --now sync-mqtt-cert.timer
```

Until the service has run once, Mosquitto restart-loops (`docker compose -f docker-compose.prod.yml ps` shows it restarting). This is expected. The service restarts it with the certificate in place. The script refuses symlinked files and a cert/key pair that do not match, and retries on the next run if the restart failed.

### 5. Verify

```bash
systemctl list-timers sync-mqtt-cert.timer
openssl s_client -connect au-parking.duckdns.org:8883 -servername au-parking.duckdns.org </dev/null
```

The `openssl` output must show a Let's Encrypt chain and `Verify return code: 0 (ok)`.

Publish over TLS from the server, using the Mosquitto image. On Amazon Linux the CA bundle is `/etc/pki/tls/certs/ca-bundle.crt` (`--capath /etc/ssl/certs` does not work there):

```bash
set -a; source .env; set +a
docker run --rm -v /etc/pki/tls/certs/ca-bundle.crt:/ca.crt:ro eclipse-mosquitto:2 mosquitto_pub \
  -h au-parking.duckdns.org -p 8883 --cafile /ca.crt -u sensor -P "$SENSOR_PASSWORD" \
  -t carcounter/spots -m '{"device_id":9,"occupied":true}'
```

In another terminal, `curl -N https://au-parking.duckdns.org/parking-lots/stream` must print `event:changed`.

### 6. Troubleshooting

```bash
sudo journalctl -u sync-mqtt-cert.service
docker compose -f docker-compose.prod.yml logs mosquitto
```

- `no ... .crt/.key found`: Caddy has no certificate yet. Check `docker compose -f docker-compose.prod.yml logs caddy`.
- Mosquitto keeps restarting: the cert is missing or does not match the key. Run `sudo systemctl start sync-mqtt-cert.service` and look at the journal.
- Connection refused or timeout on 8883: port not open in the security group, or Mosquitto is down.
- The certificate renews about every 60 days. The timer then copies it and restarts Mosquitto, which is down for a few seconds.

## Database backups

Every night at 03:00 (server time, UTC) `deploy/backup-db.sh` dumps the database to `/var/backups/au-parking/parking-db-<date>.sql.gz` and deletes dumps older than 14 days. Only root can read them. They protect against mistakes (`down -v`, a bad `UPDATE`), not against losing the disk: for that, also turn on EBS snapshots of the instance's volume in AWS (EC2 → Lifecycle Manager).

Install once, on the server from `~/au-parking` (after the files are copied):

```bash
sudo install -o root -g root -m 755 deploy/backup-db.sh /usr/local/sbin/backup-db.sh
sudo cp deploy/backup-db.service deploy/backup-db.timer /etc/systemd/system/
sudo systemctl daemon-reload
sudo systemctl start backup-db.service        # first backup now
sudo ls -l /var/backups/au-parking            # one parking-db-*.sql.gz, owned by root, mode 600
sudo systemctl enable --now backup-db.timer
```

Restore one (this replaces the current data with the dump's):

If the dump is older than the Flyway deploy, run `DROP TABLE flyway_schema_history;` before restarting parking-app.

```bash
sudo gunzip -c /var/backups/au-parking/parking-db-<date>.sql.gz | docker compose -f docker-compose.prod.yml exec -T db psql -U postgres -d parking-db
```

## One-click update

The admin dashboard's Update button makes the backend create an empty file; a systemd path unit on the host sees it and runs `deploy/au-parking-update.sh` as root (backup, `git reset --hard origin/main` as ec2-user, reinstall scripts and units, pull the image built for the new commit (`ghcr.io/nunogoncalves06/au-parking-backend:<sha>`), `up -d`, health check, rollback to the previous image and commit if it fails). The backend container has no Docker access: it mounts `/var/lib/au-parking-update` read-only, plus `inbox/` writable, which is the only place it can write. The result is in `status.json`; `au-parking-check.timer` records every 5 minutes whether the checkout is behind (`remote.json`).

Needs: `~/au-parking` is a git clone of `origin/main` (read-only deploy key; keep `.env` and `pwfile`, they are untracked), and the GHCR package is public (GitHub, after the first workflow run). The backend image runs as root inside its container, so the folders are root-owned; if a `USER` is ever added to the Dockerfile, `chown` `inbox` to its uid.

Install once, on the server from `~/au-parking`:

```bash
sudo install -d -m 755 /var/lib/au-parking-update /var/lib/au-parking-update/inbox
for s in au-parking-update au-parking-check; do sudo install -o root -g root -m 755 deploy/$s.sh /usr/local/sbin/$s.sh; done
sudo cp deploy/au-parking-update.service deploy/au-parking-update.path deploy/au-parking-check.service deploy/au-parking-check.timer /etc/systemd/system/
sudo systemctl daemon-reload
sudo systemctl enable --now au-parking-update.path au-parking-check.timer
docker compose -f docker-compose.prod.yml up -d    # remounts the folder into parking-app
```

The compose file runs the local tag `au-parking-backend:current`. An update pulls `ghcr.io/nunogoncalves06/au-parking-backend:<new commit sha>` (so the workflow must have finished: otherwise the run stops with "New version not built yet" and changes nothing), tags it `:current`, and keeps the previous image as `:rollback`. Later plain `docker compose up -d` runs keep the deployed version. Once, before the first update, seed the tag (see "Seed the image, GHCR, protect `main`" below). Updates are at most one per 5 minutes. The scripts and units installed by an update come from the new commit (fixed list), so the button keeps them current.

### Convert `~/au-parking` into a git checkout

The folder was filled by `scp`, so it is not a git repository yet. Turn it into a checkout of `origin/main` in the same folder. Nothing is moved.

What survives: `git reset --hard` only touches files that git tracks. `.env` is untracked and ignored by `.gitignore` (`*.env`), and `mqtt/config/pwfile` is ignored by `mqtt/.gitignore`, so both stay as they are (checked with `git check-ignore`). `mqtt/config/aclfile` is different: it is tracked, so the reset replaces it with the repository's copy, owned by `ec2-user`. Step 6 gives it back to uid 1883 (the update script does the same on every update).

On the server, as `ec2-user`, in `~/au-parking`:

1. Install git if missing, and make a read-only deploy key. No passphrase: the update runs unattended.

```bash
sudo dnf install -y git
mkdir -p ~/.ssh && chmod 700 ~/.ssh
ssh-keygen -t ed25519 -N '' -C au-parking-deploy -f ~/.ssh/au-parking-deploy
cat ~/.ssh/au-parking-deploy.pub
```

2. On GitHub: repository `NunoGoncalves06/Project-Work-2026-AU` > Settings > Deploy keys > Add deploy key. Paste the line printed above. Leave **Allow write access** unticked (read-only).

3. Tell ssh to use that key for GitHub, and trust GitHub's host key. Compare the printed fingerprint with the one GitHub publishes before you accept it.

```bash
cat >> ~/.ssh/config <<'EOF'
Host github.com
  IdentityFile ~/.ssh/au-parking-deploy
  IdentitiesOnly yes
EOF
chmod 600 ~/.ssh/config
ssh-keyscan -t ed25519 github.com > ~/.ssh/known_hosts.new && ssh-keygen -lf ~/.ssh/known_hosts.new
cat ~/.ssh/known_hosts.new >> ~/.ssh/known_hosts && rm ~/.ssh/known_hosts.new
ssh -T git@github.com    # "Hi NunoGoncalves06/Project-Work-2026-AU! You've successfully authenticated"
```

The update scripts run git as `ec2-user` with `HOME=/home/ec2-user`, so they use this key and `known_hosts` and never ask a question.

4. Make the folder a checkout.

```bash
cd ~/au-parking
git init -b main
git remote add origin git@github.com:NunoGoncalves06/Project-Work-2026-AU.git
git fetch origin main
```

5. Replace the copied files with the repository's. This overwrites any local edit to a tracked file, so look at `git status` first if you changed something by hand on the server.

```bash
git reset --hard origin/main
git status --short    # must not list .env or mqtt/config/pwfile
ls -l .env mqtt/config/pwfile
```

6. Give the broker its ACL file back, as the update script does (`apply_perms` in `deploy/au-parking-update.sh`):

```bash
sudo chown 1883:1883 mqtt/config/aclfile && sudo chmod 600 mqtt/config/aclfile
sudo chown 1883:1883 mqtt/config/pwfile && sudo chmod 600 mqtt/config/pwfile
chmod -R a+rX db && chmod a+r mqtt/config/mosquitto.prod.conf Caddyfile
```

### Seed the image, GHCR, protect `main`

1. **Seed `au-parking-backend:current`.** `docker-compose.prod.yml` runs `au-parking-backend:current` by default, so the tag must exist before the first `up -d` with the new compose file and before the first update. Either pull the latest build from GitHub (needs the package to be public, next step):

```bash
docker pull ghcr.io/nunogoncalves06/au-parking-backend:latest
docker tag ghcr.io/nunogoncalves06/au-parking-backend:latest au-parking-backend:current
```

   or keep the laptop-built image: `docker tag parking-app:prod au-parking-backend:current`.

2. **Make the GHCR package public.** The workflow `.github/workflows/backend-image.yml` runs on every push to `main` (tests, then build and push `:latest` and `:<commit sha>`). After its first successful run: GitHub > your profile > Packages > `au-parking-backend` > Package settings > Change visibility > Public. The image has no secrets in it (they come from `.env` at run time). The server pulls without a login, so a private package makes every update stop at "New version not built yet".

3. **Protect `main`.** Whoever can push to `main` decides what runs as root on the server: the update button checks out `origin/main` and installs the scripts and units from it. On GitHub: Settings > Branches > add a rule for `main`: require a pull request with at least 1 approval, do not allow force pushes, do not allow deletion, and include administrators if you can.

### Before the first Flyway deploy

The backend now runs Flyway (`V1__baseline.sql`, `V2__admin_schema.sql`, `V3__address_identity.sql`). On a database that already has data, Flyway adopts it as version 1 without running V1 (`spring.flyway.baseline-on-migrate=true`, `baseline-version=1`). That is only safe if the live schema really equals V1.

1. Dump the live schema and compare it with V1 by eye (tables, columns, types, keys):

```bash
cd ~/au-parking
docker compose -f docker-compose.prod.yml exec -T db pg_dump -U postgres -d parking-db --schema-only > live-schema.sql
less live-schema.sql
less parking-app/src/main/resources/db/migration/V1__baseline.sql
rm live-schema.sql
```

2. If they differ, stop and fix the difference first (or write the change as a new migration). Do not deploy.

3. Take a backup and check it exists:

```bash
sudo systemctl start backup-db.service
sudo ls -l /var/backups/au-parking
```

If you must go back to a dump older than this deploy, see the restore note under "Database backups".

## First deploy of the admin dashboard

Do these in order. Steps 1 to 4 can be done before anything changes on the server.

1. **GitHub:** protect `main` (see above). Merge the admin dashboard branch into `main` through a pull request, and wait for the `backend-image` workflow to finish (Actions tab, green).
2. **GitHub:** make the GHCR package `au-parking-backend` public.
3. **AWS:** create the Cognito pool, app client, domain, groups and users, and note the four values (`docs/admin-dashboard.md`, "Set up Cognito"). Turn on MFA for admins.
4. **Server:** make the deploy key, add it on GitHub, and convert `~/au-parking` to a git checkout (above).
5. **Server:** check the schema against V1 and take a backup (above).
6. **Server:** edit `.env`: add `APP_DB_PASSWORD` and the four `COGNITO_*` lines. No `ADMIN_AUTH`.
7. **Server:** seed `au-parking-backend:current` (above).
8. **Server:** install the update scripts and units (the install block in "One-click update"). The backup script and its units must be installed too (the update runs `/usr/local/sbin/backup-db.sh`); see "Database backups".
9. **Server:** start it, then watch the log for the Flyway migrations and for errors:

```bash
cd ~/au-parking
docker compose -f docker-compose.prod.yml up -d
docker compose -f docker-compose.prod.yml logs parking-app | tail -50
curl https://au-parking.duckdns.org/api/health
```

10. **Browser:** open `https://au-parking.duckdns.org/admin/` and sign in with one user of each role. Check: a viewer has no Traffic tab, an operator cannot press Update, an admin can add and archive a lot.
11. **Browser:** go to Server update and press the button when it says Outdated. Wait for "Update Successful". If it says "New version not built yet", the workflow is still running: wait and press again after the 5-minute cooldown.
12. Check the audit trail: `docker compose -f docker-compose.prod.yml logs parking-app | grep audit`.


## Good to know

- **Data** lives in the `pgdata` volume. `down` keeps it; `down -v` deletes it, and the init scripts then run again on the empty database.
- **Out of memory?** If the server keeps restarting (`docker compose ... ps`, `dmesg | grep -i kill`), stop the instance and change its type to t3.small.
- **The release APK** (v1.0.0 on GitHub Releases) points at `https://au-parking.duckdns.org/` and is unsigned.
