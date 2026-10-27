package au_project_work.parking_app.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

@Entity
@Table(name = "parking_spot_measurements")
public class ParkingSpotMeasurement {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "parking_spot_measurement_id")
    private Long id;

    @Column(name = "parking_spot_sensor_id", nullable = false)
    private Integer parkingSpotSensorId;

    @Column(name = "occupied", nullable = false)
    private Boolean occupied;

    @Column(name = "measured_at", nullable = false)
    private Instant measuredAt;

    protected ParkingSpotMeasurement() {
    }

    public ParkingSpotMeasurement(Integer parkingSpotSensorId, Boolean occupied, Instant measuredAt) {
        this.parkingSpotSensorId = parkingSpotSensorId;
        this.occupied = occupied;
        this.measuredAt = measuredAt;
    }
}
