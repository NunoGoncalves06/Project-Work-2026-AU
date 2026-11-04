package au_project_work.parking_app.api;

import au_project_work.parking_app.model.ParkingSpotMeasurement;
import au_project_work.parking_app.repository.ParkingSpotMeasurementRepository;
import au_project_work.parking_app.service.ParkingSpotMeasurementService;
import au_project_work.parking_app.api.Dtos.ParkingSpotMeasurementRequest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping(path = "/parking-spot-measurements")
public class ParkingSpotMeasurementController {

    @Autowired
    private ParkingSpotMeasurementRepository parkingSpotMeasurementRepository;

    @Autowired
    private ParkingSpotMeasurementService parkingSpotMeasurementService;

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public void create(@RequestBody ParkingSpotMeasurementRequest request) {
        // shared with the MQTT listener; it validates and answers 400 / 404
        parkingSpotMeasurementService.record(request.parkingSpotSensorId(), request.occupied());
    }

    @GetMapping(path = "/all")
    public Iterable<ParkingSpotMeasurement> getAllParkingSpotMeasurements() {
        return parkingSpotMeasurementRepository.findAll();
    }
}
