package au_project_work.parking_app.service;

import java.time.Instant;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import au_project_work.parking_app.model.EntranceMeasurement;
import au_project_work.parking_app.model.EntranceSensor;
import au_project_work.parking_app.repository.EntranceMeasurementRepository;
import au_project_work.parking_app.repository.EntranceSensorRepository;

@Service
public class EntranceMeasurementService {

    private final EntranceMeasurementRepository entranceMeasurementRepository;
    private final EntranceSensorRepository entranceSensorRepository;

    public EntranceMeasurementService(EntranceMeasurementRepository entranceMeasurementRepository,
            EntranceSensorRepository entranceSensorRepository) {
        this.entranceMeasurementRepository = entranceMeasurementRepository;
        this.entranceSensorRepository = entranceSensorRepository;
    }

    /**
     * Records one reading
     *
     * @throws IllegalArgumentException on a missing or invalid value, or an unknown sensor id
     */
    @Transactional
    public void record(Integer entranceSensorId, Integer measuredValue) {
        record(entranceSensorId, measuredValue, false);
    }

    /** Same path for the simulators; simulated readings are stored marked. */
    @Transactional
    public void record(Integer entranceSensorId, Integer measuredValue, boolean simulated) {
        if (entranceSensorId == null || measuredValue == null || (measuredValue != 1 && measuredValue != -1)) {
            throw new IllegalArgumentException("entranceSensorId and measuredValue (1 or -1) are required");
        }
        EntranceSensor sensor = entranceSensorRepository.findById(entranceSensorId)
                .orElseThrow(() -> new IllegalArgumentException("Unknown entrance sensor " + entranceSensorId));
        Instant now = Instant.now();
        // here we only need to save - amount of currently parked cars is determined on
        // request
        entranceMeasurementRepository.save(new EntranceMeasurement(sensor.getId(), measuredValue, now, simulated));
    }

}