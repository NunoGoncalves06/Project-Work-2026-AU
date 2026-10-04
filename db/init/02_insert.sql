INSERT INTO Addresses(address_id, city, street, house_nr)
VALUES(1, 'Aarhus', 'Finlandsgade', '22');

INSERT INTO ParkingLots(parking_lot_id, address_id)
VALUES(1, 1);

INSERT INTO ParkingRows(parking_row_id, parking_lot_id, parking_spot_amount, curr_parked_cars)
VALUES(1,1,3,0), (2,1,4,0), (3,1,5,0), (4,1,4,0);

--Sensors
INSERT INTO EntranceSensors(entrance_sensor_id, parking_lot_id)
VALUES(1, 1);

INSERT INTO ParkingRowSensors(parking_row_sensor_id, parking_row_id)
VALUES(1,1), (2,2), (3,3), (4,4);

--Measurements
INSERT INTO EntranceMeasurements (entrance_sensor_id, measured_value, measured_at)
VALUES
    (1,  1, '2026-10-04 08:00:00+02'),
    (1,  1, '2026-10-04 08:05:00+02'),
    (1,  1, '2026-10-04 08:10:00+02'),
    (1,  1, '2026-10-04 08:20:00+02'),
    (1, -1, '2026-10-04 08:30:00+02'),
    (1,  1, '2026-10-04 08:40:00+02');

INSERT INTO ParkingRowMeasurements (parking_row_sensor_id, measured_value, measured_at)
VALUES
    (1,  1, '2026-10-04 08:00:30+02'),
    (1,  1, '2026-10-04 08:05:30+02'),
    (1,  1, '2026-10-04 08:10:30+02'),   -- every parking spot is now taken
    (1, -1, '2026-10-04 08:30:30+02'),
    (1, -1, '2026-10-04 08:50:30+02');

INSERT INTO ParkingRowMeasurements (parking_row_sensor_id, measured_value, measured_at)
VALUES
    (2, 1, '2026-10-04 08:06:00+02'),
    (2, 1, '2026-10-04 08:21:00+02');