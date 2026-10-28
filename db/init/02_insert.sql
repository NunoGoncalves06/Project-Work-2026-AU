INSERT INTO addresses(address_id, city, street, house_nr, latitude, longitude)
VALUES(1, 'Aarhus', 'Finlandsgade', '22', 56.1716, 10.1897);

INSERT INTO parking_lots(parking_lot_id, address_id)
VALUES(1, 1);

INSERT INTO parking_rows(parking_row_id, parking_lot_id)
VALUES (1, 1), (2, 1), (3, 1), (4, 1);

-- row 1: spots 1-3, row 2: 4-7, row 3: 8-12, row 4: 13-16; spots 1-4 occupied
INSERT INTO parking_spots(parking_spot_id, parking_row_id, occupied, updated_at)
VALUES
    (1, 1, true, '2026-10-04 08:00:30+02'),
    (2, 1, true, '2026-10-04 08:05:30+02'),
    (3, 1, true, '2026-10-04 08:10:30+02'),
    (4, 2, true, '2026-10-04 08:21:00+02'),
    (5, 2, false, NULL), (6, 2, false, NULL), (7, 2, false, NULL),
    (8, 3, false, NULL), (9, 3, false, NULL), (10, 3, false, NULL), (11, 3, false, NULL), (12, 3, false, NULL),
    (13, 4, false, NULL), (14, 4, false, NULL), (15, 4, false, NULL), (16, 4, false, NULL);

--Sensors
INSERT INTO entrance_sensors(entrance_sensor_id, parking_lot_id)
VALUES(1, 1);

-- one sensor per spot, same id as the spot
INSERT INTO parking_spot_sensors(parking_spot_sensor_id, parking_spot_id)
SELECT parking_spot_id, parking_spot_id FROM parking_spots;

--Measurements (net +4 cars inside)
INSERT INTO entrance_measurements (entrance_sensor_id, measured_value, measured_at)
VALUES
    (1,  1, '2026-10-04 08:00:00+02'),
    (1,  1, '2026-10-04 08:05:00+02'),
    (1,  1, '2026-10-04 08:10:00+02'),
    (1,  1, '2026-10-04 08:20:00+02'),
    (1, -1, '2026-10-04 08:30:00+02'),
    (1,  1, '2026-10-04 08:40:00+02');

INSERT INTO parking_spot_measurements (parking_spot_sensor_id, occupied, measured_at)
VALUES
    (1, true,  '2026-10-04 08:00:30+02'),
    (2, true,  '2026-10-04 08:05:30+02'),
    (3, true,  '2026-10-04 08:10:30+02'),
    (4, true,  '2026-10-04 08:06:00+02'),
    (4, false, '2026-10-04 08:15:00+02'),
    (4, true,  '2026-10-04 08:21:00+02');
