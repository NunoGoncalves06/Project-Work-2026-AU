package au_project_work.parking_app.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "entrance_sensors")
public class EntranceSensor {

    @Id
    @Column(name = "entrance_sensor_id")
    private Integer id;

    public Integer getId() {
        return id;
    }
}
