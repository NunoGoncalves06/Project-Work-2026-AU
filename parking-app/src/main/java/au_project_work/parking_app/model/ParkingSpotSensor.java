package au_project_work.parking_app.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OneToOne;
import jakarta.persistence.Table;

@Entity
@Table(name = "parking_spot_sensors")
public class ParkingSpotSensor {

    @Id
    @Column(name = "parking_spot_sensor_id")
    private Integer id;

    @OneToOne(optional = false)
    @JoinColumn(name = "parking_spot_id", unique = true)
    private ParkingSpot parkingSpot;

    public Integer getId() {
        return id;
    }

    public ParkingSpot getParkingSpot() {
        return parkingSpot;
    }
}
