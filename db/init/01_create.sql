CREATE TABLE addresses
(
    address_id INTEGER PRIMARY KEY,
    city VARCHAR(50) NOT NULL,
    street VARCHAR(50) NOT NULL,
    house_nr VARCHAR(10) NOT NULL
);

CREATE TABLE parking_lots
(
    parking_lot_id INTEGER PRIMARY KEY,
    address_id INTEGER REFERENCES addresses NOT NULL
);

-- entrance/exit: +1 car in, -1 car out
CREATE TABLE entrance_sensors
(
    entrance_sensor_id INTEGER PRIMARY KEY NOT NULL,
    parking_lot_id INTEGER REFERENCES parking_lots NOT NULL
);

CREATE TABLE entrance_measurements
(
    entrance_measurement_id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    entrance_sensor_id INTEGER REFERENCES entrance_sensors NOT NULL,
    measured_value INTEGER NOT NULL CHECK (measured_value IN (1, -1)),
    measured_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE parking_rows
(
    parking_row_id INTEGER PRIMARY KEY,
    parking_lot_id INTEGER REFERENCES parking_lots NOT NULL
);

-- occupied is the current state, updated by the API on every spot sensor message
CREATE TABLE parking_spots
(
    parking_spot_id INTEGER PRIMARY KEY,
    parking_row_id INTEGER REFERENCES parking_rows NOT NULL,
    occupied BOOLEAN NOT NULL DEFAULT false,
    updated_at TIMESTAMPTZ
);

CREATE TABLE parking_spot_sensors
(
    parking_spot_sensor_id INTEGER PRIMARY KEY,
    parking_spot_id INTEGER REFERENCES parking_spots NOT NULL UNIQUE
);

CREATE TABLE parking_spot_measurements
(
    parking_spot_measurement_id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    parking_spot_sensor_id INTEGER REFERENCES parking_spot_sensors NOT NULL,
    occupied BOOLEAN NOT NULL,
    measured_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
