#!/usr/bin/env bash
# Self-check for au-parking-update.sh with fake git, docker, systemctl, flock and runuser; needs no root, systemd or docker.
set -euo pipefail
here=$(cd "$(dirname "$0")" && pwd)
t=$(mktemp -d); trap 'rm -rf "$t"' EXIT
export T=$t
OLD=$(printf 'a%.0s' {1..40}); NEW=$(printf 'b%.0s' {1..40}); IMG="sha256:$(printf 'c%.0s' {1..64})"
REPO=ghcr.io/nunogoncalves06/au-parking-backend

mkdir -p "$t/app/db" "$t/app/mqtt/config" "$t/upd/inbox" "$t/sbin" "$t/units" "$t/bin"
touch "$t/app/Caddyfile" "$t/app/mqtt/config/aclfile" "$t/app/mqtt/config/mosquitto.prod.conf" "$t/app/docker-compose.prod.yml"
printf '#!/bin/sh\necho backup >> "$T/calls"\n[ ! -e "$T/backup_fail" ]\n' > "$t/bin/backup"
printf '#!/bin/sh\necho "$*" >> "$T/flocklog"\n[ ! -e "$T/locked" ]\n' > "$t/bin/flock"
printf '#!/bin/sh\nshift 3\nexec "$@"\n' > "$t/bin/runuser" # drops: -u USER --
printf '#!/bin/sh\necho "systemctl $*" >> "$T/calls"\n' > "$t/bin/systemctl"
cat > "$t/bin/git" <<'EOS'
#!/bin/sh
echo "git $*" >> "$T/calls"
[ "$1" = -C ] && shift 2
case "$1" in
  rev-parse) [ "$2" = HEAD ] && cat "$T/head" || echo "$NEW_SHA" ;;
  reset) echo "$3" > "$T/head" ;;
  show) echo "content of $2" ;;
  diff) [ ! -e "$T/diff_changed" ] ;;
esac
EOS
cat > "$t/bin/docker" <<'EOS'
#!/bin/sh
echo "PARKING_APP_IMAGE=${PARKING_APP_IMAGE:-} docker $*" >> "$T/calls"
case "$*" in
  *"ps -q"*) echo container1 ;;
  "image inspect"*) [ -e "$T/have_new" ] && echo "$OLD_IMG" || exit 1 ;;
  inspect*) echo "$OLD_IMG" ;;
  pull*) [ ! -e "$T/no_tag" ] ;;
  *" up -d"*) [ "${PARKING_APP_IMAGE:-}" = au-parking-backend:rollback ] && rm -f "$T/health_fail" || true ;;
  *" exec "*) [ ! -e "$T/health_fail" ] && [ ! -e "$T/health_fail_always" ] ;;
esac
EOS
chmod +x "$t"/bin/*
export NEW_SHA=$NEW OLD_IMG=$IMG
export APP_DIR="$t/app" UPDATE_DIR="$t/upd" LOCK_FILE="$t/lock" SBIN_DIR="$t/sbin" UNIT_DIR="$t/units" ACL_OWNER="$(id -u):$(id -g)" \
  BACKUP_SCRIPT="$t/bin/backup" GIT="$t/bin/git" DOCKER="$t/bin/docker" SYSTEMCTL="$t/bin/systemctl" FLOCK="$t/bin/flock" \
  RUNUSER="$t/bin/runuser" HEALTH_TRIES=2 HEALTH_SLEEP=0 COOLDOWN_SECS=0

ok() { echo "PASS: $1"; }
fail() { echo "FAIL: $1"; echo "--- status:"; cat "$t/upd/status.json" 2>/dev/null || true; echo "--- calls:"; cat "$t/calls" 2>/dev/null || true; exit 1; }
reset() { rm -f "$t/calls" "$t/flocklog" "$t/upd/status.json" "$t"/{locked,have_new,no_tag,backup_fail,health_fail,health_fail_always,diff_changed}; echo "$OLD" > "$t/head"; }
run() { rm -f "$t/calls"; : > "$t/upd/inbox/request"; bash "$here/au-parking-update.sh"; }
has() { grep -q -- "$1" "$t/calls"; }
hasnt() { ! grep -q -- "$1" "$t/calls" 2>/dev/null; }
status_has() { grep -q -- "$1" "$t/upd/status.json"; }
gone() { [ ! -e "$t/upd/inbox/request" ]; }
DCF="docker compose -f $t/app/docker-compose.prod.yml"

# success
reset
run || fail "success run exits 0"
status_has '"state":"success"' && status_has "\"sha\":\"$NEW\"" && status_has "\"previousSha\":\"$OLD\"" || fail "status success with both shas"
gone || fail "request removed"
has backup && has "docker pull $REPO:$NEW" && has "docker tag $IMG au-parking-backend:rollback" \
  && has "docker tag $REPO:$NEW au-parking-backend:current" && has "$DCF up -d" && has "systemctl daemon-reload" || fail "backup, pull sha tag, tags, up, daemon-reload"
[ "$(cat "$t/sbin/au-parking-update.sh")" = "content of $NEW:deploy/au-parking-update.sh" ] && [ -e "$t/units/au-parking-update.path" ] || fail "installed from the new commit"
ok "success: backup, pull of the sha tag, rollback tag, current tag, install from the commit, status success"

# changed Caddyfile / mqtt config
reset; touch "$t/diff_changed"
run || fail "changed-config run exits 0"
has "up -d --force-recreate --no-deps caddy" && has "$DCF restart mosquitto" || fail "caddy recreated, mosquitto restarted"
ok "changed Caddyfile and mqtt config: caddy recreated, mosquitto restarted"

# already up to date: same commit, same image -> nothing touched but the status
reset; echo "$NEW" > "$t/head"; touch "$t/have_new"
run || fail "up-to-date run exits 0"
status_has '"state":"success"' && status_has "Already up to date" && hasnt backup && hasnt "docker pull" || fail "up to date skips backup and pull"
gone || fail "request removed"
ok "already up to date: success, no backup, no pull"

# health check fails: rolled back
reset; touch "$t/health_fail"
if run; then fail "failed health check must exit non-zero"; fi
has "PARKING_APP_IMAGE=au-parking-backend:rollback $DCF up -d" && has "docker tag au-parking-backend:rollback au-parking-backend:current" || fail "rollback runs the saved image"
[ "$(grep -c 'systemctl daemon-reload' "$t/calls")" = 2 ] && has "git .*show $OLD:deploy/au-parking-update.sh" || fail "rollback reinstalls scripts and units from the old commit"
[ "$(cat "$t/head")" = "$OLD" ] || fail "checkout reset to previous commit"
status_has '"state":"error"' && status_has "Update failed, rolled back" || fail "status rolled back"
ok "health check fails: old commit, scripts and image restored and health-checked, status rolled back"

# rollback health check fails too
reset; touch "$t/health_fail_always"
if run; then fail "must exit non-zero"; fi
status_has "rollback failed" || fail "status rollback failed"
ok "rollback that does not get healthy is reported as rollback failed"

# backup fails: nothing changed
reset; touch "$t/backup_fail"
if run; then fail "backup failure must exit non-zero"; fi
status_has "Backup failed" && gone && hasnt "docker pull" && hasnt "git .*reset" || fail "backup failure changes nothing"
ok "backup failure: nothing changed, request removed, status error"

# new image tag does not exist yet
reset; touch "$t/no_tag"
if run; then fail "missing tag must exit non-zero"; fi
status_has "New version not built yet" && status_has '"state":"error"' && gone && hasnt "git .*reset" && hasnt "docker tag" && hasnt " up -d" || fail "missing tag deploys nothing"
ok "missing image tag: nothing deployed, git untouched, status error"

# busy lock: waits (flock -w), gives up without touching anything, request still consumed
reset; touch "$t/locked"; : > "$t/upd/inbox/request"
bash "$here/au-parking-update.sh" || fail "busy run exits 0"
[ ! -e "$t/calls" ] && [ ! -e "$t/upd/status.json" ] && gone || fail "busy run touches nothing and consumes the request"
grep -q -- '-w 300' "$t/flocklog" || fail "busy run waits with flock -w 300"
ok "busy lock: waited with -w 300, touched nothing, request removed"

# cooldown
reset; printf '{"state":"success","sha":"","previousSha":"","startedAt":"","finishedAt":"%s","message":"x"}\n' "$(date -u +%Y-%m-%dT%H:%M:%SZ)" > "$t/upd/status.json"
cp "$t/upd/status.json" "$t/status.before"
COOLDOWN_SECS=300 run || fail "cooldown run exits 0"
cmp -s "$t/upd/status.json" "$t/status.before" && gone && [ ! -e "$t/calls" ] || fail "cooldown leaves status alone and runs nothing"
ok "cooldown: a run right after another does nothing, request removed"

# symlinked Caddyfile is refused
reset; rm "$t/app/Caddyfile"; ln -s "$t/bin/backup" "$t/app/Caddyfile"
if [ ! -L "$t/app/Caddyfile" ]; then echo "SKIP: symlink refusal (ln -s makes copies on this system)"
else
  if run; then fail "symlinked Caddyfile must exit non-zero"; fi
  status_has '"state":"error"' && hasnt " up -d" || fail "symlinked Caddyfile refused before anything runs"
  ok "symlinked Caddyfile refused, nothing started"
fi
echo "ALL PASSED"
