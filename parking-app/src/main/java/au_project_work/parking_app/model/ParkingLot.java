package au_project_work.parking_app.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.Table;

import java.util.List;

@Entity
@Table(name = "parking_lots")
public class ParkingLot {

    @Id
    @Column(name = "parking_lot_id")
    private Integer id;

    @ManyToOne(optional = false)
    @JoinColumn(name = "address_id")
    private Address address;

    @OneToMany(mappedBy = "parkingLot")
    @OrderBy("id")
    private List<ParkingRow> rows;

    public Integer getId() {
        return id;
    }

    public Address getAddress() {
        return address;
    }

    public List<ParkingRow> getRows() {
        return rows;
    }
}
