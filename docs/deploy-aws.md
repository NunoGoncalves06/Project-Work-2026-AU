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

Always use `docker-compose.prod.yml` on the server. Never run the plain `docker compose up` (the dev `docker-compose.yml`): it publishes an anonymous broker on port 1883.

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

```bash
sudo gunzip -c /var/backups/au-parking/parking-db-<date>.sql.gz | docker compose -f docker-compose.prod.yml exec -T db psql -U postgres -d parking-db
```

## Good to know

- **Data** lives in the `pgdata` volume. `down` keeps it; `down -v` deletes it, and the init scripts then run again on the empty database.
- **Out of memory?** If the server keeps restarting (`docker compose ... ps`, `dmesg | grep -i kill`), stop the instance and change its type to t3.small.
- **The release APK** still points at the emulator address and is unsigned; use the debug build above for demos.
