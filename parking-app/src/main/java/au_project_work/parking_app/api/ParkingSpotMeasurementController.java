package au_project_work.parking_app.api;

import au_project_work.parking_app.model.ParkingSpotMeasurement;
import au_project_work.parking_app.repository.ParkingSpotMeasurementRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

// read-only: readings arrive over MQTT (SpotMeasurementListener)
@RestController
@RequestMapping(path = "/parking-spot-measurements")
public class ParkingSpotMeasurementController {

    @Autowired
    private ParkingSpotMeasurementRepository parkingSpotMeasurementRepository;

    @GetMapping(path = "/all")
    public Iterable<ParkingSpotMeasurement> getAllParkingSpotMeasurements() {
        return parkingSpotMeasurementRepository.findAll();
    }
}
