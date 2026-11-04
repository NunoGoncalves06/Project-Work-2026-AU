package au_project_work.parking_app.repository;

import au_project_work.parking_app.model.ParkingLot;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface ParkingLotRepository extends JpaRepository<ParkingLot, Integer> {

    /**
     * A short string that changes whenever anything the app displays changes. Four
     * aggregates over small or primary-key-indexed columns, so it stays cheap to run
     * on a timer; ParkingUpdates uses it to notice edits made straight in the
     * database (psql, a DB client), which reach no application code and so fire no
     * event.
     *
     * <p>
     * Blind spot: two spots flipping in opposite directions between two checks leave
     * the occupied count equal. updated_at catches that whenever the API made the
     * change; a hand-written UPDATE that leaves updated_at alone is only picked up by
     * the next change.
     */
    @Query(value = """
            SELECT (SELECT count(*) FROM parking_spots)
                || ':' || (SELECT count(*) FROM parking_spots WHERE occupied)
                || ':' || coalesce((SELECT max(updated_at) FROM parking_spots)::text, '-')
                || ':' || coalesce((SELECT max(entrance_measurement_id) FROM entrance_measurements)::text, '-')
            """, nativeQuery = true)
    String fingerprint();
}
