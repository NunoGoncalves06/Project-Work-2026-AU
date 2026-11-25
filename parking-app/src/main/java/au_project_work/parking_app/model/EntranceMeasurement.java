package au_project_work.parking_app.model;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Column;

import java.time.Instant;

@Entity
@Table(name = "entrance_measurements")
public class EntranceMeasurement {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "entrance_measurement_id")
    private Long entranceMeasurementId;

    @Column(name = "entrance_sensor_id", nullable = false)
    private Integer entranceSensorId;

    @Column(name = "measured_value", nullable = false)
    private Integer measuredValue;

    @Column(name = "measured_at", nullable = false)
    private Instant measuredAt;

    @Column(name = "simulated", nullable = false)
    private boolean simulated;

    protected EntranceMeasurement() {
    }

    public EntranceMeasurement(Integer entranceSensorId, Integer measuredValue, Instant measuredAt, boolean simulated) {
        this.simulated = simulated;
        this.entranceSensorId = entranceSensorId;
        this.measuredValue = measuredValue;
        this.measuredAt = measuredAt;
    }
}
