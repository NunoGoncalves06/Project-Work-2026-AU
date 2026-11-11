package au_project_work.parking_app.api;

import au_project_work.parking_app.model.EntranceMeasurement;
import au_project_work.parking_app.repository.EntranceMeasurementRepository;
import au_project_work.parking_app.repository.EntranceSensorRepository;
import au_project_work.parking_app.api.Dtos.EntranceMeasurementRequest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;

@RestController
@RequestMapping(path = "/entrance-measurements")
public class EntranceMeasurementController {

  @Autowired
  private EntranceMeasurementRepository entranceMeasurementRepository;

  @Autowired
  private EntranceSensorRepository entranceSensorRepository;

  @GetMapping(path = "/all")
  public Iterable<EntranceMeasurement> getAllEntranceMeasurements() {
    return entranceMeasurementRepository.findAll();
  }

  @GetMapping(path = "/sensor/{sensorId}")
  public Iterable<EntranceMeasurement> bySensor(@PathVariable Integer sensorId) {
    return entranceMeasurementRepository.findAllByEntranceSensorIdOrderByMeasuredAtAsc(sensorId);
  }

  @PostMapping
  @ResponseStatus(HttpStatus.CREATED)
  public void create(@RequestBody EntranceMeasurementRequest request) {
    Integer value = request.measuredValue();
    if (request.entranceSensorId() == null || value == null || (value != 1 && value != -1)) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
          "entranceSensorId and measuredValue (1 or -1) are required");
    }
    if (!entranceSensorRepository.existsById(request.entranceSensorId())) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Unknown entrance sensor " + request.entranceSensorId());
    }
    entranceMeasurementRepository.save(new EntranceMeasurement(request.entranceSensorId(), value, Instant.now()));
  }
}
