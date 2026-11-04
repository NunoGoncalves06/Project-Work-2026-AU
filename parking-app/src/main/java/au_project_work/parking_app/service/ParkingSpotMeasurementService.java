package au_project_work.parking_app.service;

import org.springframework.stereotype.Service;
import java.time.Instant;

import au_project_work.parking_app.model.ParkingSpotMeasurement;
import au_project_work.parking_app.repository.ParkingSpotMeasurementRepository;

@Service
public class ParkingSpotMeasurementService {

    private final ParkingSpotMeasurementRepository parkingSpotMeasurementRepository;

    public ParkingSpotMeasurementService(ParkingSpotMeasurementRepository parkingSpotMeasurementRepository) {
        this.parkingSpotMeasurementRepository = parkingSpotMeasurementRepository;
    }

    // just to test - we should probably use a dto later on
    public void SaveParkingSpotMeasurement(int device_id, Boolean occupied) {
        Instant measured_at = Instant.now();
        ParkingSpotMeasurement parkingSpotMeasurement = new ParkingSpotMeasurement(device_id,
                occupied,
                measured_at);
        // save measurement to database
        parkingSpotMeasurementRepository.save(parkingSpotMeasurement);
    }
}
