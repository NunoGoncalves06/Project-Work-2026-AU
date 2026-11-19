package au_project_work.parking_app.repository;

import au_project_work.parking_app.model.EntranceMeasurement;

import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.CrudRepository;

public interface EntranceMeasurementRepository extends CrudRepository<EntranceMeasurement, Long> {

    // Sum of every entrance reading ever. A missed or repeated sensor message stays in the sum
    // for good; carsInsideResyncedAt4am fixes that. Clamped at 0 so a missed entry can't show
    // negative cars.
    @Query(value = """
            SELECT GREATEST(0, COALESCE(SUM(m.measured_value), 0))
            FROM entrance_measurements m
            JOIN entrance_sensors s ON s.entrance_sensor_id = m.entrance_sensor_id
            WHERE s.parking_lot_id = :parkingLotId
            """, nativeQuery = true)
    long carsInside(Integer parkingLotId);

    /**
     * Cars inside, resynced daily to the spot sensors: the spots that were occupied at the last
     * 04:00 Aarhus time, plus the entrance readings since then. Each spot reading is a state, not
     * a +1/-1, so the start value is reliable, and entrance drift is gone after a day at most. The
     * lot can stay open: it only assumes nobody is driving around looking for a spot at 04:00.
     * Not used yet (demo testing); switch in ParkingLotController.toDetail.
     *
     * shortcut: reads the lot's whole spot history on each call; add an index on
     * parking_spot_measurements (parking_spot_sensor_id, measured_at) if that history gets big.
     */
    @Query(value = """
            WITH last4am AS (
                SELECT (date_trunc('day', (now() AT TIME ZONE 'Europe/Copenhagen') - interval '4 hours')
                        + interval '4 hours') AT TIME ZONE 'Europe/Copenhagen' AS at
            ),
            spot_state_at_4am AS (
                SELECT DISTINCT ON (m.parking_spot_sensor_id) m.occupied
                FROM parking_spot_measurements m
                JOIN parking_spot_sensors ss ON ss.parking_spot_sensor_id = m.parking_spot_sensor_id
                JOIN parking_spots sp ON sp.parking_spot_id = ss.parking_spot_id
                JOIN parking_rows r ON r.parking_row_id = sp.parking_row_id
                WHERE r.parking_lot_id = :parkingLotId
                  AND m.measured_at < (SELECT at FROM last4am)
                ORDER BY m.parking_spot_sensor_id, m.measured_at DESC
            )
            SELECT GREATEST(0,
                (SELECT count(*) FROM spot_state_at_4am WHERE occupied)
                + COALESCE((SELECT SUM(m.measured_value)
                            FROM entrance_measurements m
                            JOIN entrance_sensors s ON s.entrance_sensor_id = m.entrance_sensor_id
                            WHERE s.parking_lot_id = :parkingLotId
                              AND m.measured_at >= (SELECT at FROM last4am)), 0))
            """, nativeQuery = true)
    long carsInsideResyncedAt4am(Integer parkingLotId);
}