package au_project_work.parking_app.api;

import au_project_work.parking_app.model.EntranceMeasurement;
import au_project_work.parking_app.repository.EntranceMeasurementRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

// read-only: readings arrive over MQTT (EntranceMeasurementListener)
@RestController
@RequestMapping(path = "/entrance-measurements")
public class EntranceMeasurementController {

  @Autowired
  private EntranceMeasurementRepository entranceMeasurementRepository;

  @GetMapping(path = "/all")
  public Iterable<EntranceMeasurement> getAllEntranceMeasurements() {
    return entranceMeasurementRepository.findAll();
  }

  @GetMapping(path = "/sensor/{sensorId}")
  public Iterable<EntranceMeasurement> bySensor(@PathVariable Integer sensorId) {
    return entranceMeasurementRepository.findAllByEntranceSensorIdOrderByMeasuredAtAsc(sensorId);
  }
}
