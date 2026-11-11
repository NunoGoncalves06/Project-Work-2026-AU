-- Demo history: 4 weeks of spot sensor readings, every 30 minutes for every spot, so the
-- forecast on the lot screen has something to show before the real sensors have run that long.
-- Busy on weekday working hours, quiet at night and on weekends.
--
-- Run once (it adds rows each time):
--   docker compose exec db psql -U postgres -d parking-db -f /scripts/demo_history.sql
-- Undo: deletes every reading on the half hour with zero seconds, which real sensors almost never hit:
--   DELETE FROM parking_spot_measurements WHERE extract(second FROM measured_at) = 0
--       AND extract(minute FROM measured_at) IN (0, 30) AND measured_at > '2026-10-05';

-- also in 01_create.sql; here for databases created before it was added
CREATE INDEX IF NOT EXISTS parking_spot_measurements_sensor_time_idx
    ON parking_spot_measurements (parking_spot_sensor_id, measured_at);

SELECT setseed(0.42);

INSERT INTO parking_spot_measurements (parking_spot_sensor_id, occupied, measured_at)
SELECT ss.parking_spot_sensor_id,
       random() < CASE
           WHEN extract(isodow FROM l.local) <= 5 THEN
               CASE WHEN extract(hour FROM l.local) BETWEEN 9 AND 15 THEN 0.85
                    WHEN extract(hour FROM l.local) IN (7, 8, 16, 17) THEN 0.5
                    ELSE 0.1 END
           ELSE
               CASE WHEN extract(hour FROM l.local) BETWEEN 10 AND 17 THEN 0.3 ELSE 0.05 END
       END,
       t
FROM parking_spot_sensors ss,
     generate_series(date_trunc('hour', now()) - interval '28 days', now() - interval '1 hour', interval '30 minutes') t,
     LATERAL (SELECT t AT TIME ZONE 'Europe/Copenhagen' AS local) l;
