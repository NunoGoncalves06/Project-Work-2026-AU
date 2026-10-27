package au_project_work.parking_app.repository;

import au_project_work.parking_app.model.EntranceMeasurement;

import java.util.List;

import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.CrudRepository;

public interface EntranceMeasurementRepository extends CrudRepository<EntranceMeasurement, Long> {

    List<EntranceMeasurement> findAllByEntranceSensorIdOrderByMeasuredAtAsc(Integer entranceSensorId);

    // clamped at 0 so a missed exit event can't show negative cars
    @Query(value = """
            SELECT GREATEST(0, COALESCE(SUM(m.measured_value), 0))
            FROM entrance_measurements m
            JOIN entrance_sensors s ON s.entrance_sensor_id = m.entrance_sensor_id
            WHERE s.parking_lot_id = :parkingLotId
            """, nativeQuery = true)
    long carsInside(Integer parkingLotId);
}