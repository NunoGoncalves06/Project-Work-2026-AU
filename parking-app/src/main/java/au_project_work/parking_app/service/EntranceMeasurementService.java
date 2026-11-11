package au_project_work.parking_app.service;

import java.time.Instant;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
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
     * @throws ResponseStatusException 404 if no sensor has that id
     */
    @Transactional
    public void record(Integer entranceSensorId, Integer measuredValue) {
        if (entranceSensorId == null || measuredValue == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "entranceSensorId, measuredValue and measuredAt are required.");
        }
        EntranceSensor sensor = entranceSensorRepository.findById(entranceSensorId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "Unknown entrance sensor " + entranceSensorId));
        Instant now = Instant.now();
        // here we only need to save - amount of currently parked cards is determined on
        // request
        entranceMeasurementRepository.save(new EntranceMeasurement(sensor.getId(), measuredValue, now));
    }

}