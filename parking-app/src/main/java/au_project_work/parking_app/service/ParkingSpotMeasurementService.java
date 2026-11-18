package au_project_work.parking_app.service;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

import au_project_work.parking_app.model.ParkingSpotMeasurement;
import au_project_work.parking_app.repository.ParkingSpotMeasurementRepository;
import au_project_work.parking_app.repository.ParkingSpotSensorRepository;

/**
 * The one place a spot sensor reading is recorded (called by the MQTT listener).
 * It updates parking_spots.occupied as well as appending history: the MQTT path
 * used to only append history, which left the app showing stale numbers however
 * often it refreshed.
 */
@Service
public class ParkingSpotMeasurementService {

    private final ParkingSpotMeasurementRepository parkingSpotMeasurementRepository;
    private final ParkingSpotSensorRepository parkingSpotSensorRepository;

    public ParkingSpotMeasurementService(ParkingSpotMeasurementRepository parkingSpotMeasurementRepository,
            ParkingSpotSensorRepository parkingSpotSensorRepository) {
        this.parkingSpotMeasurementRepository = parkingSpotMeasurementRepository;
        this.parkingSpotSensorRepository = parkingSpotSensorRepository;
    }

    /**
     * Records one reading: updates the spot's current state and appends the history
     * row, both in the same transaction.
     *
     * @throws IllegalArgumentException on a missing or invalid value, or an unknown sensor id
     */
    @Transactional
    public void record(Integer parkingSpotSensorId, Boolean occupied) {
        record(parkingSpotSensorId, occupied, false);
    }

    /** Same path for the simulators; simulated readings are stored marked so the forecast can skip them. */
    @Transactional
    public void record(Integer parkingSpotSensorId, Boolean occupied, boolean simulated) {
        if (parkingSpotSensorId == null || occupied == null) {
            throw new IllegalArgumentException("parkingSpotSensorId and occupied are required");
        }
        // existsById does not join the spot, so a sensor of an archived spot still counts
        if (!parkingSpotSensorRepository.existsById(parkingSpotSensorId)) {
            throw new IllegalArgumentException("Unknown parking spot sensor " + parkingSpotSensorId);
        }
        Instant now = Instant.now();
        // managed entity: the update is flushed at commit, in the same transaction as
        // the history row. Empty for an archived spot: only the history row is stored.
        parkingSpotSensorRepository.findById(parkingSpotSensorId)
                .ifPresent(sensor -> sensor.getParkingSpot().updateOccupied(occupied, now));
        parkingSpotMeasurementRepository.save(new ParkingSpotMeasurement(parkingSpotSensorId, occupied, now, simulated));
    }
}
