package au_project_work.parking_app.repository;

import au_project_work.parking_app.model.ParkingSpotMeasurement;

import org.springframework.data.repository.CrudRepository;

public interface ParkingSpotMeasurementRepository extends CrudRepository<ParkingSpotMeasurement, Long> {
}
