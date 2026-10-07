
package au_project_work.parking_app;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;

@RestController
@RequestMapping(path = "/entrance-measurements")
public class EntranceMeasurementController {

  @Autowired
  private EntranceMeasurementRepository entranceMeasurementRepository;

  @GetMapping(path = "/test")
  public String test() {
    return "Response from EntranceMeasurementController.";
  }

  @GetMapping(path = "/all")
  public Iterable<EntranceMeasurement> getAllEntranceMeasurements() {
    return entranceMeasurementRepository.findAll();
  }

  @GetMapping(path = "/sensor/{sensorId}")
  public Iterable<EntranceMeasurement> bySensor(@PathVariable Integer sensorId) {
    return entranceMeasurementRepository.findAllByEntranceSensorId(sensorId);
  }
}