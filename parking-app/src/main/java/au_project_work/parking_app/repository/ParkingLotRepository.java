package au_project_work.parking_app.repository;

import au_project_work.parking_app.model.ParkingLot;

import java.time.Instant;
import java.util.List;

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
            SELECT (SELECT count(*) FROM parking_spots WHERE archived_at IS NULL)
                || ':' || (SELECT count(*) FROM parking_spots WHERE occupied AND archived_at IS NULL)
                || ':' || coalesce((SELECT max(archived_at) FROM (
                        SELECT archived_at FROM parking_lots UNION ALL SELECT archived_at FROM parking_rows
                        UNION ALL SELECT archived_at FROM parking_spots) a)::text, '-')
                || ':' || coalesce((SELECT max(updated_at) FROM parking_spots)::text, '-')
                || ':' || coalesce((SELECT max(entrance_measurement_id) FROM entrance_measurements)::text, '-')
            """, nativeQuery = true)
    String fingerprint();

    interface HourForecast {
        Instant getAt();

        int getFreeSpots();
    }

    /**
     * Free spots usually seen at each of the next 168 full hours (a week, so the app can offer any
     * day and hour): for every hour, the lot's
     * state at that time on the same weekday in each of the last 4 weeks (from the spot
     * history), averaged. Weeks before the history starts are skipped, so a lot with no
     * history gets an empty list instead of "all free".
     */
    @Query(value = """
            WITH spots AS (
                SELECT ss.parking_spot_sensor_id AS sensor_id
                FROM parking_spot_sensors ss
                JOIN parking_spots sp ON sp.parking_spot_id = ss.parking_spot_id
                JOIN parking_rows r ON r.parking_row_id = sp.parking_row_id
                WHERE r.parking_lot_id = :parkingLotId
                  AND sp.archived_at IS NULL AND r.archived_at IS NULL
            ),
            hours AS (
                SELECT date_trunc('hour', now() AT TIME ZONE 'Europe/Copenhagen') + h * interval '1 hour' AS local_hour
                FROM generate_series(1, 168) h
            ),
            samples AS (
                SELECT local_hour, (local_hour - w * interval '1 week') AT TIME ZONE 'Europe/Copenhagen' AS t
                FROM hours, generate_series(1, 4) w
            ),
            occupied AS (
                SELECT s.local_hour,
                       (SELECT count(*) FROM spots WHERE (
                            SELECT m.occupied FROM parking_spot_measurements m
                            WHERE m.parking_spot_sensor_id = spots.sensor_id AND NOT m.simulated AND m.measured_at <= s.t
                            ORDER BY m.measured_at DESC LIMIT 1)) AS n
                FROM samples s
                WHERE s.t >= (SELECT min(m.measured_at) FROM parking_spot_measurements m
                              WHERE NOT m.simulated AND m.parking_spot_sensor_id IN (SELECT sensor_id FROM spots))
            )
            SELECT local_hour AT TIME ZONE 'Europe/Copenhagen' AS at,
                   round((SELECT count(*) FROM spots) - avg(n))::int AS "freeSpots"
            FROM occupied
            GROUP BY local_hour
            ORDER BY local_hour
            """, nativeQuery = true)
    List<HourForecast> forecast(Integer parkingLotId);
}
