#!/usr/bin/env bash
# Every 5 minutes: fetches origin/main as ec2-user (the working tree is never touched) and records
# where the checkout stands, for the dashboard's Outdated / Updated state. Root; no arguments.
set -euo pipefail
umask 022
PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin

APP_USER=${APP_USER:-ec2-user}
APP_DIR=${APP_DIR:-/home/ec2-user/au-parking}
UPDATE_DIR=${UPDATE_DIR:-/var/lib/au-parking-update}
LOCK_FILE=${LOCK_FILE:-/run/au-parking-update.lock}
GIT=${GIT:-git} FLOCK=${FLOCK:-flock} RUNUSER=${RUNUSER:-runuser}

as_user() { "$RUNUSER" -u "$APP_USER" -- env HOME="$(dirname "$APP_DIR")" GIT_TERMINAL_PROMPT=0 "$@"; }

exec 9>"$LOCK_FILE"
"$FLOCK" -n 9 || exit 0 # an update is running: it moves the checkout, so check next time

as_user timeout 60 "$GIT" -C "$APP_DIR" fetch origin main >/dev/null 2>&1
local_sha=$(as_user "$GIT" -C "$APP_DIR" rev-parse HEAD)
remote_sha=$(as_user "$GIT" -C "$APP_DIR" rev-parse origin/main)
[[ $local_sha =~ ^[0-9a-f]{40}$ && $remote_sha =~ ^[0-9a-f]{40}$ ]] || { echo "au-parking-check: unexpected git output" >&2; exit 1; }
printf '{"localSha":"%s","remoteSha":"%s","checkedAt":"%s"}\n' "$local_sha" "$remote_sha" "$(date -u +%Y-%m-%dT%H:%M:%SZ)" \
  > "$UPDATE_DIR/remote.json.tmp"
mv -f "$UPDATE_DIR/remote.json.tmp" "$UPDATE_DIR/remote.json"
