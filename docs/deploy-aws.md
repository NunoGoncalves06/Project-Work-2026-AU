# Deploying to AWS

The whole stack (server, Postgres, Mosquitto) runs on one EC2 instance with Docker Compose, behind Caddy for HTTPS. Files: `docker-compose.prod.yml`, `Caddyfile`, `mqtt/config/mosquitto.prod.conf`, `.env.example`. The local `docker-compose.yml` is unchanged and still what you use on your laptop.

## The instance

- Amazon Linux 2023, **64-bit (x86)**, t3.micro, 20 GiB gp3, credit specification **Standard**.
- Security group inbound: **22 from your IP only**, **80 and 443 from anywhere**. Do not open 5432 or 8080. Open 1883 only when real sensors need it, and only from their IP.
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

ssh -i $key $srv "mkdir -p au-parking/db au-parking/mqtt/config"
scp -i $key docker-compose.prod.yml Caddyfile parking-app.tar "${srv}:au-parking/"
scp -i $key -r db/init "${srv}:au-parking/db/"
scp -i $key mqtt/config/mosquitto.prod.conf "${srv}:au-parking/mqtt/config/"
Remove-Item parking-app.tar
```

Then on the server (`ssh -i $key $srv`):

```bash
cd au-parking
docker load -i parking-app.tar && rm parking-app.tar
# scp from Windows leaves the files readable only by ec2-user; Postgres and Mosquitto run as
# other users inside their containers, and Postgres exits with code 2 if it can't list db/init
chmod -R a+rX db && chmod a+r mqtt/config/mosquitto.prod.conf Caddyfile
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

Start (and, on later deploys, restart with the new image):

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

On the server, publish a reading and watch the stream say `changed`:

```bash
docker compose -f docker-compose.prod.yml exec mosquitto mosquitto_pub -u sensor -P '<sensor password>' \
  -t carcounter/spots -m '{"device_id":9,"occupied":true}'
```

The phone app: build it against the server, no `adb reverse` needed.

```bash
cd android-app && ./gradlew :app:installDebug -PapiBaseUrl=https://<DOMAIN>/
```

## Good to know

- **Data** lives in the `pgdata` volume. `down` keeps it; `down -v` deletes it, and the init scripts then run again on the empty database.
- **Out of memory?** If the server keeps restarting (`docker compose ... ps`, `dmesg | grep -i kill`), stop the instance and change its type to t3.small.
- **The release APK** still points at the emulator address and is unsigned; use the debug build above for demos.
