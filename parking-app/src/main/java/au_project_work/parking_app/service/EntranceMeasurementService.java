package au_project_work.parking_app.service;

import org.springframework.stereotype.Service;

import au_project_work.parking_app.model.EntranceMeasurement;
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

    // use dto that is mapped to entity either here or in the repository?
    public void SaveEntranceMeasurement() {
    }

}