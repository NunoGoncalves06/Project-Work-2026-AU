-- when the sensor was last attached to its spot; the lot forecast ignores older readings (they belong to the old spot). NULL = since always
ALTER TABLE parking_spot_sensors ADD COLUMN attached_at TIMESTAMPTZ;
