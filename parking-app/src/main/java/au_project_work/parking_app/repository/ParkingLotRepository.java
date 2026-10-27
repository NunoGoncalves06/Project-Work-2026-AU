package au_project_work.parking_app.repository;

import au_project_work.parking_app.model.ParkingLot;

import org.springframework.data.jpa.repository.JpaRepository;

public interface ParkingLotRepository extends JpaRepository<ParkingLot, Integer> {
}
