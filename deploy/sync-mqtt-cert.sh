#!/usr/bin/env bash
# Copies Caddy's Let's Encrypt cert for the domain to Mosquitto's cert dir; restarts mosquitto only if it changed.
# Run as root from the project folder. Every CERT_*/RESTART_CMD/DOMAIN value is overridable for tests.
set -euo pipefail

DOMAIN=${DOMAIN:-au-parking.duckdns.org}
# root-owned and outside /home: ec2-user can't swap files for symlinks while root writes them
CERT_DEST_DIR=${CERT_DEST_DIR:-/etc/au-parking/mqtt-certs}
CERT_OWNER=${CERT_OWNER:-1883:1883}
RESTART_CMD=${RESTART_CMD:-docker compose -f docker-compose.prod.yml restart mosquitto}
CERT_SRC_DIR=${CERT_SRC_DIR:-$(docker volume inspect au-parking_caddydata -f '{{.Mountpoint}}')/caddy/certificates}

# The issuer folder varies (Let's Encrypt or ZeroSSL fallback), so take the newest cert across all of them.
crt=$(ls -t "$CERT_SRC_DIR"/*/"$DOMAIN/$DOMAIN.crt" 2>/dev/null | head -n1) || true
key=${crt%.crt}.key
if [ -z "$crt" ] || [ ! -f "$key" ]; then
  echo "sync-mqtt-cert: no $DOMAIN.crt/.key found under $CERT_SRC_DIR/*/$DOMAIN/" >&2
  exit 1
fi
# The volume is written by the Caddy container; a planted symlink would make host root copy any host file.
if [ -L "$crt" ] || [ -L "$key" ]; then
  echo "sync-mqtt-cert: refusing symlinked cert or key in $(dirname "$crt")" >&2
  exit 1
fi
# A mismatched pair would stop Mosquitto from starting at all (taking down the 1883 listener too), so never install one.
if ! cmp -s <(openssl x509 -in "$crt" -noout -pubkey) <(openssl pkey -in "$key" -pubout); then
  echo "sync-mqtt-cert: cert and key in $(dirname "$crt") do not match; keeping the existing copy" >&2
  exit 1
fi

# The marker survives a failed restart, so the next run retries even though the files already match.
if [ ! -e "$CERT_DEST_DIR/.restart-pending" ] && cmp -s "$crt" "$CERT_DEST_DIR/server.crt" && cmp -s "$key" "$CERT_DEST_DIR/server.key"; then
  exit 0
fi

mkdir -p "$CERT_DEST_DIR"
# Temp files in the destination dir so mv is an atomic rename; Mosquitto never sees a half-written file.
tmpc=$(mktemp "$CERT_DEST_DIR/.server.crt.XXXXXX")
tmpk=$(mktemp "$CERT_DEST_DIR/.server.key.XXXXXX")
trap 'rm -f "$tmpc" "$tmpk"' EXIT
cp "$crt" "$tmpc"
cp "$key" "$tmpk"
chown "$CERT_OWNER" "$tmpc" "$tmpk"
chmod 644 "$tmpc"
chmod 600 "$tmpk"
touch "$CERT_DEST_DIR/.restart-pending"
mv "$tmpc" "$CERT_DEST_DIR/server.crt"
mv "$tmpk" "$CERT_DEST_DIR/server.key"

$RESTART_CMD
rm -f "$CERT_DEST_DIR/.restart-pending"
