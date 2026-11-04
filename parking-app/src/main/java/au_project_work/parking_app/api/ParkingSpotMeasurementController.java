package au_project_work.parking_app.api;

import au_project_work.parking_app.model.ParkingSpotMeasurement;
import au_project_work.parking_app.model.ParkingSpotSensor;
import au_project_work.parking_app.repository.ParkingSpotMeasurementRepository;
import au_project_work.parking_app.repository.ParkingSpotSensorRepository;
import au_project_work.parking_app.api.Dtos.ParkingSpotMeasurementRequest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;

@RestController
@RequestMapping(path = "/parking-spot-measurements")
public class ParkingSpotMeasurementController {

    @Autowired
    private ParkingSpotSensorRepository parkingSpotSensorRepository;

    @Autowired
    private ParkingSpotMeasurementRepository parkingSpotMeasurementRepository;

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Transactional
    public void create(@RequestBody ParkingSpotMeasurementRequest request) {
        if (request.parkingSpotSensorId() == null || request.occupied() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "parkingSpotSensorId and occupied are required");
        }
        ParkingSpotSensor sensor = parkingSpotSensorRepository.findById(request.parkingSpotSensorId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "Unknown parking spot sensor " + request.parkingSpotSensorId()));
        Instant now = Instant.now();
        // managed entity: the update is flushed at commit, in the same transaction as
        // the history row
        sensor.getParkingSpot().updateOccupied(request.occupied(), now);
        parkingSpotMeasurementRepository.save(new ParkingSpotMeasurement(sensor.getId(), request.occupied(), now));
    }

    @GetMapping(path = "/all")
    public Iterable<ParkingSpotMeasurement> getAllParkingSpotMeasurements() {
        return parkingSpotMeasurementRepository.findAll();
    }
}
