package au_project_work.parking_app;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Column;

import java.time.LocalDateTime;

@Entity
@Table(name = "entrance_measurements")
public class EntranceMeasurement {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "entrance_measurement_id")
    private Long entranceMeasurementId;

    @Column(name = "entrance_sensor_id")
    private Integer entranceSensorId;

    @Column(name = "measured_value", nullable = false)
    private Integer measuredValue;

    @Column(name = "measured_at", nullable = false)
    private LocalDateTime measuredAt;

    public Long getEntranceMeasurementID() {
        return entranceMeasurementId;
    }

    public Integer getEntranceSensorID() {
        return entranceSensorId;
    }

    public Integer getMeasuredValue() {
        return measuredValue;
    }

    public LocalDateTime getMeasuredAt() {
        return measuredAt;
    }
}