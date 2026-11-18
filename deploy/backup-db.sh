#!/usr/bin/env bash
# Dumps the parking database to a gzipped SQL file and deletes dumps older than KEEP_DAYS.
# Run as root from the project folder (systemd: backup-db.service). Values are overridable for tests.
set -euo pipefail

BACKUP_DIR=${BACKUP_DIR:-/var/backups/au-parking}
COMPOSE_FILE=${COMPOSE_FILE:-docker-compose.prod.yml}
KEEP_DAYS=${KEEP_DAYS:-14}

umask 077 # the dumps hold every reading: only root may read them
mkdir -p "$BACKUP_DIR"
out="$BACKUP_DIR/parking-db-$(date -u +%Y%m%d-%H%M%S).sql.gz"
trap 'rm -f "$out.part"' EXIT

# --clean --if-exists: restoring drops and recreates the tables instead of failing on existing ones.
# Written under .part first, so a failed dump never looks like a good backup or triggers the cleanup.
docker compose -f "$COMPOSE_FILE" exec -T db pg_dump -U postgres -d parking-db --clean --if-exists | gzip > "$out.part"
mv "$out.part" "$out"
find "$BACKUP_DIR" -name 'parking-db-*.sql.gz' -mtime +"$KEEP_DAYS" -delete
echo "backup-db: wrote $out"
