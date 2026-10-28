-- Demo data for testing the app with several lots: NOT real occupancy. Streets are real Aarhus addresses,
-- coordinates approximate. Safe to re-run (skips rows that exist). Load with:
--   docker compose exec db sh -c 'psql -U "$POSTGRES_USER" -d "$POSTGRES_DB" -f /scripts/demo_lots.sql'
-- Remove by resetting the DB: docker compose down -v && docker compose up -d

INSERT INTO addresses(address_id, city, street, house_nr, latitude, longitude) VALUES
    (2, 'Aarhus', 'Nordre Ringgade', '1', 56.1681, 10.2030),
    (3, 'Aarhus', 'Åbogade', '34', 56.1745, 10.1915),
    (4, 'Aarhus', 'Ny Munkegade', '120', 56.1700, 10.2005)
ON CONFLICT DO NOTHING;

INSERT INTO parking_lots(parking_lot_id, address_id) VALUES (2, 2), (3, 3), (4, 4)
ON CONFLICT DO NOTHING;

-- lot 2: 2 rows of 6 (rows 20-21), lot 3: 3 rows of 8 (rows 30-32), lot 4: 2 rows of 10 (rows 40-41)
INSERT INTO parking_rows(parking_row_id, parking_lot_id) VALUES (20, 2), (21, 2), (30, 3), (31, 3), (32, 3), (40, 4), (41, 4)
ON CONFLICT DO NOTHING;

-- spot ids = row id * 100 + n; lot 2 is completely full, lot 3 about half, lot 4 nearly full
INSERT INTO parking_spots(parking_spot_id, parking_row_id, occupied, updated_at)
SELECT r * 100 + n, r,
       CASE WHEN r IN (20, 21) THEN true
            WHEN r BETWEEN 30 AND 32 THEN n % 2 = 0
            ELSE n <> 3 AND n <> 7 END,
       now()
FROM (VALUES (20, 6), (21, 6), (30, 8), (31, 8), (32, 8), (40, 10), (41, 10)) AS rows(r, cnt),
     generate_series(1, cnt) AS n
ON CONFLICT DO NOTHING;

-- one sensor per spot, same id as the spot (as in the seed), so the measurement endpoint works on them too
INSERT INTO parking_spot_sensors(parking_spot_sensor_id, parking_spot_id)
SELECT parking_spot_id, parking_spot_id FROM parking_spots WHERE parking_spot_id >= 2000
ON CONFLICT DO NOTHING;
