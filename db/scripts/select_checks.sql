SELECT r.parking_row_id, count(*) AS spots, count(*) FILTER (WHERE NOT s.occupied) AS free
FROM parking_rows r JOIN parking_spots s USING (parking_row_id)
GROUP BY r.parking_row_id ORDER BY r.parking_row_id;

SELECT * FROM entrance_measurements
ORDER BY entrance_sensor_id, measured_at;

SELECT * FROM parking_spot_measurements
ORDER BY parking_spot_sensor_id, measured_at;
