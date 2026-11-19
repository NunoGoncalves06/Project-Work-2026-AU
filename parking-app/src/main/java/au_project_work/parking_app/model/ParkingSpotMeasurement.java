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

    @Column(name = "simulated", nullable = false)
    private boolean simulated;

    protected ParkingSpotMeasurement() {
    }

    public ParkingSpotMeasurement(Integer parkingSpotSensorId, Boolean occupied, Instant measuredAt, boolean simulated) {
        this.parkingSpotSensorId = parkingSpotSensorId;
        this.occupied = occupied;
        this.measuredAt = measuredAt;
        this.simulated = simulated;
    }

    public boolean isSimulated() {
        return simulated;
    }

    public Long getParkingSpotMeasurementId() {
        return id;
    }

    public Integer getParkingSpotSensorId() {
        return parkingSpotSensorId;
    }

    public Boolean getOccupied() {
        return occupied;
    }

    public Instant getMeasuredAt() {
        return measuredAt;
    }
}
