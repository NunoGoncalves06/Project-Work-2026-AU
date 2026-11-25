-- how a lot's free spots are counted: 'spots' = a sensor on every spot, 'entrance' = only a gate sensor counting cars in and out
ALTER TABLE parking_lots ADD COLUMN counted_by TEXT NOT NULL DEFAULT 'spots' CHECK (counted_by IN ('spots','entrance'));
-- simulated gate readings (operator tools), like parking_spot_measurements.simulated
ALTER TABLE entrance_measurements ADD COLUMN simulated BOOLEAN NOT NULL DEFAULT false;
