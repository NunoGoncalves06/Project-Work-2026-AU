#!/usr/bin/env bash
# Self-check for sync-mqtt-cert.sh on a fake Caddy volume; needs no root, systemd or docker.
set -euo pipefail
here=$(cd "$(dirname "$0")" && pwd)
t=$(mktemp -d); trap 'rm -rf "$t"' EXIT
d=au-parking.duckdns.org
# gen DIR: real throwaway ECDSA P-256 cert+key (what Caddy issues) as DIR/$d.crt and .key; MSYS2_ARG_CONV_EXCL stops Git Bash turning -subj /CN=.. into a path
gen() {
  mkdir -p "$1"
  MSYS2_ARG_CONV_EXCL=/CN= openssl req -x509 -newkey ec -pkeyopt ec_paramgen_curve:prime256v1 -nodes -days 1 -subj "/CN=$d" \
    -keyout "$1/$d.key" -out "$1/$d.crt" 2>/dev/null
}
mkdir -p "$t/dest"
s1="$t/src/acme-v02.api.letsencrypt.org-directory/$d"
gen "$s1"; cp "$s1/$d.crt" "$t/crt1"; cp "$s1/$d.key" "$t/key1"
printf '#!/bin/sh\necho restarted >> "%s/restarts"\n' "$t" > "$t/fake-restart"; chmod +x "$t/fake-restart"
export DOMAIN=$d CERT_SRC_DIR="$t/src" CERT_DEST_DIR="$t/dest" CERT_OWNER="$(id -u):$(id -g)" RESTART_CMD="$t/fake-restart"
run() { bash "$here/sync-mqtt-cert.sh"; }
restarts() { { cat "$t/restarts" 2>/dev/null || true; } | wc -l | tr -d ' '; }
ok() { echo "PASS: $1"; }
fail() { echo "FAIL: $1"; exit 1; }
same() { cmp -s "$t/dest/server.crt" "$1/$d.crt" && cmp -s "$t/dest/server.key" "$1/$d.key"; }

run
cmp -s "$t/dest/server.crt" "$t/crt1" && cmp -s "$t/dest/server.key" "$t/key1" || fail "first run copies"
[ "$(restarts)" = 1 ] || fail "first run restarts once"
ok "first run copies and restarts"
touch "$t/probe"; chmod 600 "$t/probe"
if [ "$(stat -c %a "$t/probe")" != 600 ]; then echo "SKIP: key mode 600 (this filesystem ignores chmod)"
else [ "$(stat -c %a "$t/dest/server.key")" = 600 ] || fail "key mode 600"; ok "key mode 600"; fi
run
[ "$(restarts)" = 1 ] || fail "second run must not restart"
ok "second run with same files does nothing"
gen "$s1"; cp "$s1/$d.crt" "$t/crt2"; cp "$s1/$d.key" "$t/key2"
run
[ "$(restarts)" = 2 ] && cmp -s "$t/dest/server.crt" "$t/crt2" || fail "changed cert restarts"
ok "changed cert is copied and restarts"
rm "$s1/$d.key"
if run 2>"$t/err"; then fail "missing key must exit non-zero"; fi
[ -s "$t/err" ] && cmp -s "$t/dest/server.crt" "$t/crt2" && cmp -s "$t/dest/server.key" "$t/key2" && [ "$(restarts)" = 2 ] || fail "missing key keeps old copy"
ok "missing key exits non-zero, old copy kept, error: $(cat "$t/err")"
rm -r "$t/src"/*
if run 2>/dev/null; then fail "missing cert must exit non-zero"; fi
cmp -s "$t/dest/server.crt" "$t/crt2" || fail "missing cert keeps old copy"
ok "missing cert exits non-zero, old copy kept"

# Failed restart must be retried next run even though the files already match.
gen "$t/src/le"/$d
n=$(restarts)
printf '#!/bin/sh\nexit 1\n' > "$t/fail-restart"; chmod +x "$t/fail-restart"
if RESTART_CMD="$t/fail-restart" run 2>/dev/null; then fail "failing restart must exit non-zero"; fi
[ -e "$t/dest/.restart-pending" ] && same "$t/src/le/$d" || fail "marker left after failed restart"
run
[ "$(restarts)" = $((n + 1)) ] && [ ! -e "$t/dest/.restart-pending" ] || fail "retry restarts and clears marker"
ok "failed restart is retried on unchanged files and marker cleared"
run
[ "$(restarts)" = $((n + 1)) ] || fail "no restart once marker cleared"

# Newest cert across issuer folders wins (e.g. ZeroSSL fallback after a Let's Encrypt cert).
gen "$t/src/zerossl/$d"
touch -d '2 days ago' "$t/src/le/$d/$d.crt"
run
same "$t/src/zerossl/$d" || fail "newest issuer copied"
ok "newest issuer folder is copied"

# A cert whose key does not match would stop Mosquitto starting; it must be refused and the old copy kept.
gen "$t/src/bad/$d"; gen "$t/other/$d"; cp "$t/other/$d/$d.key" "$t/src/bad/$d/$d.key"
n=$(restarts)
if run 2>"$t/err"; then fail "mismatched key must exit non-zero"; fi
[ -s "$t/err" ] && same "$t/src/zerossl/$d" && [ "$(restarts)" = "$n" ] || fail "mismatched key keeps old copy"
ok "mismatched cert/key exits non-zero, old copy kept, error: $(cat "$t/err")"
rm -r "$t/src/bad"

# A symlinked cert in the volume is refused and the old copy kept.
gen "$t/src/sym/$d"; rm "$t/src/sym/$d/$d.crt"; ln -s "$t/probe" "$t/src/sym/$d/$d.crt"
if [ ! -L "$t/src/sym/$d/$d.crt" ]; then echo "SKIP: symlink refusal (ln -s makes copies on this system)"
else
  if run 2>/dev/null; then fail "symlinked cert must exit non-zero"; fi
  same "$t/src/zerossl/$d" || fail "symlinked cert keeps old copy"
  ok "symlinked cert is refused, old copy kept"
fi
echo "ALL PASSED"
