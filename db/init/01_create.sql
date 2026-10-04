CREATE TABLE Addresses
(address_id INTEGER PRIMARY KEY,
city VARCHAR(50) NOT NULL,
street VARCHAR(50) NOT NULL,
house_nr VARCHAR(10) NOT NULL
);

CREATE TABLE ParkingLots
(
    parking_lot_id INTEGER PRIMARY KEY,
    address_id INTEGER REFERENCES Addresses NOT NULL
);

CREATE TABLE EntranceSensors
(
    entrance_sensor_id INTEGER PRIMARY KEY NOT NULL,
    parking_lot_id INTEGER REFERENCES ParkingLots NOT NULL
);

CREATE TABLE EntranceMeasurements
(
    entrance_measurement_id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    entrance_sensor_id INTEGER REFERENCES EntranceSensors NOT NULL,
    measured_value INTEGER NOT NULL,
    measured_at TIMESTAMP NOT NULL DEFAULT now()
);

--add to EntranceMeasurements: changed_parked_cars_total_to INTEGER

--CREATE INDEX ON EntranceMeasurements (entrance_sensor_id, measured_at DESC);

--Create and define Trigger function calc_parked_cars_total()

CREATE TABLE ParkingRows
(
    parking_row_id INTEGER PRIMARY KEY,
    parking_lot_id INTEGER REFERENCES ParkingLots NOT NULL,
    parking_spot_amount INTEGER NOT NULL,
    curr_parked_cars INTEGER NOT NULL DEFAULT 0,
    CHECK (curr_parked_cars BETWEEN 0 AND parking_spot_amount)
);

CREATE TABLE ParkingRowSensors
(
    parking_row_sensor_id INTEGER PRIMARY KEY,
    parking_row_id INTEGER REFERENCES ParkingRows NOT NULL
);

CREATE TABLE ParkingRowMeasurements
(
    parking_row_measurement_id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    parking_row_sensor_id INTEGER REFERENCES ParkingRowSensors NOT NULL,
    measured_value INTEGER NOT NULL,
    measured_at TIMESTAMP NOT NULL DEFAULT now()
);

--add to ParkingRowMeasurements: changed_parked_cars_row_to INTEGER

--CREATE INDEX on ParkingRowMeasurements (parking_row_sensor_id, measured_at DESC);

--CREATE OR REPLACE FUNCTION calc_parked_cars_row()
--RETURNS TRIGGER AS $$
--BEGIN
-- insert logic
--END;
--$$ LANGUAGE plpgsql;

--CREATE TRIGGER trigger_calc_parked_cars_row
--BEFORE INSERT ON ParkingRowMeasurements
--FOR EACH ROW 
--EXECUTE FUNCTION calc_parked_cars_row();