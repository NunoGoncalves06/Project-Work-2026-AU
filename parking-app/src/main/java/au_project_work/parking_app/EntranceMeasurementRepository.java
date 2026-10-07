package au_project_work.parking_app;

import java.util.List;

import org.springframework.data.repository.CrudRepository;

public interface EntranceMeasurementRepository extends CrudRepository<EntranceMeasurement, Long> {

    List<EntranceMeasurement> findAllByEntranceSensorId(Integer entranceSensorId);
}