#!/usr/bin/env bash
# One-click update of the stack. Root, no arguments, reads no input: the backend only creates the file
# $UPDATE_DIR/inbox/request, and only its existence starts au-parking-update.path -> au-parking-update.service.
# Progress and result go to $UPDATE_DIR/status.json (fixed messages only, never command output).
# Overrides (GIT, DOCKER, ...) exist for deploy/test-au-parking-update.sh; the systemd unit unsets them.
set -euo pipefail
umask 022
PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin

APP_USER=${APP_USER:-ec2-user}
APP_DIR=${APP_DIR:-/home/ec2-user/au-parking}
UPDATE_DIR=${UPDATE_DIR:-/var/lib/au-parking-update}
LOCK_FILE=${LOCK_FILE:-/run/au-parking-update.lock}
SBIN_DIR=${SBIN_DIR:-/usr/local/sbin}
UNIT_DIR=${UNIT_DIR:-/etc/systemd/system}
ACL_OWNER=${ACL_OWNER:-1883:1883}
BACKUP_SCRIPT=${BACKUP_SCRIPT:-/usr/local/sbin/backup-db.sh}
GIT=${GIT:-git} DOCKER=${DOCKER:-docker} SYSTEMCTL=${SYSTEMCTL:-systemctl} FLOCK=${FLOCK:-flock} RUNUSER=${RUNUSER:-runuser}
HEALTH_TRIES=${HEALTH_TRIES:-30} HEALTH_SLEEP=${HEALTH_SLEEP:-3} COOLDOWN_SECS=${COOLDOWN_SECS:-300}

IMAGE_REPO=ghcr.io/nunogoncalves06/au-parking-backend # tags there are full commit shas (the workflow github.sha)
CURRENT=au-parking-backend:current   # what docker-compose.prod.yml runs by default: survives a plain `up -d`
ROLLBACK=au-parking-backend:rollback # the image that ran before this update
STATUS=$UPDATE_DIR/status.json
REQ=$UPDATE_DIR/inbox/request
SCRIPTS=(backup-db.sh sync-mqtt-cert.sh au-parking-update.sh au-parking-check.sh)
UNITS=(backup-db.service backup-db.timer sync-mqtt-cert.service sync-mqtt-cert.timer
  au-parking-update.service au-parking-update.path au-parking-check.service au-parking-check.timer)

now() { date -u +%Y-%m-%dT%H:%M:%SZ; }
sha_ok() { [[ ${1:-} =~ ^[0-9a-f]{40}$ ]] && printf '%s' "$1" || true; }
as_user() { "$RUNUSER" -u "$APP_USER" -- env HOME="$(dirname "$APP_DIR")" GIT_TERMINAL_PROMPT=0 "$@"; }
dc() { "$DOCKER" compose -f "$APP_DIR/docker-compose.prod.yml" "$@"; }

started=""; prev=""; new=""; final=0; begun=0
write_status() { # state message [finishedAt]
  printf '{"state":"%s","sha":"%s","previousSha":"%s","startedAt":"%s","finishedAt":"%s","message":"%s"}\n' \
    "$1" "$new" "$prev" "$started" "${3:-}" "$2" > "$STATUS.tmp"
  mv -f "$STATUS.tmp" "$STATUS"
}
finish() { final=1; write_status "$1" "$2" "$(now)"; }
# the trigger is consumed on every exit path, so a failed or skipped run can never leave the dashboard on "Updating"
trap 'rm -rf -- "$REQ"; if [ "$begun" = 1 ] && [ "$final" = 0 ]; then finish error "Update interrupted"; fi' EXIT

exec 9>"$LOCK_FILE"
"$FLOCK" -w 300 9 || exit 0 # still busy after 5 minutes: give up, touch nothing else
rm -rf -- "$REQ"

# at most one run per cooldown: repeated requests cannot keep the server busy
last=$(grep -o '"finishedAt":"[^"]*"' "$STATUS" 2>/dev/null | cut -d'"' -f4 || true)
if [ -n "$last" ] && last_s=$(date -u -d "$last" +%s 2>/dev/null); then
  age=$(( $(date -u +%s) - last_s ))
  if [ "$age" -ge 0 ] && [ "$age" -lt "$COOLDOWN_SECS" ]; then exit 0; fi
fi

begun=1; started=$(now)
write_status updating "Update started"
cd "$APP_DIR"

as_user timeout 120 "$GIT" -C "$APP_DIR" fetch origin main >/dev/null 2>&1 \
  || { finish error "Could not fetch the new version, nothing changed"; exit 1; }
new=$(sha_ok "$(as_user "$GIT" -C "$APP_DIR" rev-parse origin/main)")
prev=$(sha_ok "$(as_user "$GIT" -C "$APP_DIR" rev-parse HEAD)")
[ -n "$new" ] && [ -n "$prev" ] || { finish error "Unexpected git answer, nothing changed"; exit 1; }
target=$IMAGE_REPO:$new

cid=$(dc ps -q parking-app | head -n1) || cid=""
prev_image=""
if [ -n "$cid" ]; then prev_image=$("$DOCKER" inspect --format '{{.Image}}' "$cid") || prev_image=""; fi
[[ $prev_image =~ ^sha256:[0-9a-f]{64}$ ]] || prev_image=""

have=$("$DOCKER" image inspect --format '{{.Id}}' "$target" 2>/dev/null || true)
if [ "$prev" = "$new" ] && [ -n "$prev_image" ] && [ "$have" = "$prev_image" ]; then
  finish success "Already up to date"
  exit 0
fi

"$BACKUP_SCRIPT" >/dev/null 2>&1 || { finish error "Backup failed, nothing changed"; exit 1; }
# the image for this commit must exist before anything on the server changes
"$DOCKER" pull "$target" >/dev/null 2>&1 || { finish error "New version not built yet, nothing changed"; exit 1; }
if [ -n "$prev_image" ]; then
  "$DOCKER" tag "$prev_image" "$ROLLBACK" || { finish error "Could not save the running version, nothing changed"; exit 1; }
fi
as_user "$GIT" -C "$APP_DIR" reset --hard "$new" >/dev/null 2>&1 \
  || { finish error "Could not check out the new version"; exit 1; }

# git rewrites files as ec2-user: give the container users their files back. Symlinks are refused (chmod would follow them)
apply_perms() {
  local p
  for p in Caddyfile mqtt/config mqtt/config/aclfile mqtt/config/mosquitto.prod.conf db; do
    if [ -L "$p" ]; then return 1; fi
  done
  chown -h "$ACL_OWNER" mqtt/config/aclfile && chmod 600 mqtt/config/aclfile || return 1
  chmod -R a+rX db && chmod a+r mqtt/config/mosquitto.prod.conf Caddyfile || return 1
}
# installs only the fixed lists above, straight from the commit (never from the working tree), into a root-owned
# temp file renamed over the target: the running copy of this script keeps its old file
install_one() { # rev name dir mode
  local tmp
  tmp=$(mktemp "$3/$2.XXXXXX") || return 1
  if as_user "$GIT" -C "$APP_DIR" show "$1:deploy/$2" > "$tmp" 2>/dev/null; then
    chmod "$4" "$tmp" && mv -f "$tmp" "$3/$2"
  else
    rm -f "$tmp" # not in that commit: nothing to install
  fi
}
install_deploy_files() { # rev
  local f
  for f in "${SCRIPTS[@]}"; do install_one "$1" "$f" "$SBIN_DIR" 0755 || return 1; done
  for f in "${UNITS[@]}"; do install_one "$1" "$f" "$UNIT_DIR" 0644 || return 1; done
  "$SYSTEMCTL" daemon-reload
}
# single files and folders bind-mounted into a container keep their old inode when git replaces them:
# a reload would see the old Caddyfile, and Mosquitto reads its config only at start
refresh_services() {
  as_user "$GIT" -C "$APP_DIR" diff --quiet "$prev" "$new" -- Caddyfile || dc up -d --force-recreate --no-deps caddy || return 1
  as_user "$GIT" -C "$APP_DIR" diff --quiet "$prev" "$new" -- mqtt/config || dc restart mosquitto || return 1
}
health() {
  local i
  for ((i = 0; i < HEALTH_TRIES; i++)); do
    dc exec -T caddy wget -q -T 3 -O /dev/null http://parking-app:8080/api/health && return 0
    sleep "$HEALTH_SLEEP"
  done
  return 1
}
deploy_new() {
  apply_perms || return 1
  install_deploy_files "$new" || return 1
  "$DOCKER" tag "$target" "$CURRENT" || return 1
  dc up -d || return 1
  refresh_services || return 1
  health
}
roll_back() {
  [ -n "$prev_image" ] || return 1
  as_user "$GIT" -C "$APP_DIR" reset --hard "$prev" || return 1
  apply_perms || return 1
  install_deploy_files "$prev" || return 1
  "$DOCKER" tag "$ROLLBACK" "$CURRENT" || return 1 # later plain `up -d` runs keep the old version too
  PARKING_APP_IMAGE=$ROLLBACK dc up -d || return 1
  refresh_services || return 1
  health
}

if deploy_new >/dev/null 2>&1; then
  finish success "Update successful"
  exit 0
fi

# Roll back: checkout, scripts and image go back together. Migrations are additive, so the previous image
# runs on the migrated database.
if roll_back >/dev/null 2>&1; then
  finish error "Update failed, rolled back"
else
  finish error "Update failed, rollback failed"
fi
exit 1
