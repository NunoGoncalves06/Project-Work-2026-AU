package au_project_work.parking_app.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import org.hibernate.annotations.SQLRestriction;

import java.time.Instant;

@Entity
@SQLRestriction("archived_at IS NULL")
@Table(name = "parking_spots")
public class ParkingSpot {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "parking_spot_id")
    private Integer id;

    @Column(name = "archived_at")
    private Instant archivedAt;

    @ManyToOne(optional = false, fetch = FetchType.LAZY)
    @JoinColumn(name = "parking_row_id")
    private ParkingRow parkingRow;

    @Column(name = "occupied", nullable = false)
    private Boolean occupied;

    @Column(name = "updated_at")
    private Instant updatedAt;

    public Integer getId() {
        return id;
    }

    public Boolean getOccupied() {
        return occupied;
    }

    public void updateOccupied(boolean occupied, Instant at) {
        this.occupied = occupied;
        this.updatedAt = at;
    }
}
