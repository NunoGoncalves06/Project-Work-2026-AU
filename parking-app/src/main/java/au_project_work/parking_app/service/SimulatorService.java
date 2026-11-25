package au_project_work.parking_app.service;

import au_project_work.parking_app.api.Dtos.TrafficStatusDto;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ScheduledFuture;
import java.util.random.RandomGenerator;

/**
 * The operator simulators: one spot toggle, and one traffic simulation at a time. Readings go through the same
 * record() as the sensors, marked simulated. start, stop and tick share one lock, so a tick after stop is a no-op.
 */
@Service
public class SimulatorService {

    private static final Logger AUDIT = LoggerFactory.getLogger("audit");
    private static final Duration MAX_RUN = Duration.ofMinutes(60);
    // chance that a tick moves away from the target, so the lot looks like traffic and not a ramp
    private static final double NOISE = 0.2;

    // spots shown in the app (spot, row and lot all unarchived) that have a sensor
    private static final String SENSOR_SPOTS = """
            SELECT sp.parking_spot_id, s.parking_spot_sensor_id, sp.occupied
            FROM parking_spot_sensors s
            JOIN parking_spots sp ON sp.parking_spot_id = s.parking_spot_id
            JOIN parking_rows r ON r.parking_row_id = sp.parking_row_id
            JOIN parking_lots l ON l.parking_lot_id = r.parking_lot_id
            WHERE sp.archived_at IS NULL AND r.archived_at IS NULL AND l.archived_at IS NULL
            """;

    private record SensorSpot(int spotId, int sensorId, boolean occupied) {
    }

    private final JdbcTemplate jdbc;
    private final ParkingSpotMeasurementService measurements;
    private final EntranceMeasurementService gateReadings;
    private final TaskScheduler scheduler;
    private final Clock clock;
    private final RandomGenerator random;

    private final Object lock = new Object();
    // all below guarded by lock; task == null means nothing runs
    private ScheduledFuture<?> task;
    private int lotId;
    private boolean gateLot; // the lot is counted at the entrance: the run sends gate readings
    private double target;
    private int changesPerMinute;
    private Instant startedAt;
    private Instant endsAt;
    private int changes;
    // spot -> its occupied value when the simulation first changed it
    private final Map<Integer, Boolean> touched = new HashMap<>();

    public SimulatorService(JdbcTemplate jdbc, ParkingSpotMeasurementService measurements, EntranceMeasurementService gateReadings,
            TaskScheduler scheduler, Clock clock, RandomGenerator random) {
        this.gateReadings = gateReadings;
        this.jdbc = jdbc;
        this.measurements = measurements;
        this.scheduler = scheduler;
        this.clock = clock;
        this.random = random;
    }

    /** @throws ResponseStatusException 404 when the spot is archived or has no sensor */
    public void setSpot(int spotId, boolean occupied) {
        List<Integer> sensor = jdbc.queryForList(
                "SELECT x.parking_spot_sensor_id FROM (" + SENSOR_SPOTS + " AND sp.parking_spot_id = ?) x",
                Integer.class, spotId);
        if (sensor.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Not found");
        }
        measurements.record(sensor.get(0), occupied, true);
    }

    /** One simulated car in (1) or out (-1) at the lot's first entrance sensor. @throws ResponseStatusException 400 for another dir, 404 when the lot is unknown, archived or has no gate */
    public void gate(int lotId, int dir) {
        if (dir != 1 && dir != -1) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "dir must be 1 or -1");
        }
        List<Integer> gate = gatesOf(lotId);
        if (gate.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Not found");
        }
        gateReadings.record(gate.get(0), dir, true);
    }

    private List<Integer> gatesOf(int lotId) {
        return jdbc.queryForList("SELECT s.entrance_sensor_id FROM entrance_sensors s JOIN parking_lots l ON l.parking_lot_id = s.parking_lot_id "
                + "WHERE l.parking_lot_id = ? AND l.archived_at IS NULL ORDER BY s.entrance_sensor_id", Integer.class, lotId);
    }

    /** @throws ResponseStatusException 404 for an unknown or archived lot, 409 when a simulation is running or an entrance lot has no gate */
    public void start(int lotId, double target, int changesPerMinute) {
        synchronized (lock) {
            if (task != null) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "Already running");
            }
            List<String> counted = jdbc.queryForList(
                    "SELECT counted_by FROM parking_lots WHERE parking_lot_id = ? AND archived_at IS NULL", String.class, lotId);
            if (counted.isEmpty()) {
                throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Not found");
            }
            boolean gate = "entrance".equals(counted.get(0));
            if (gate && gatesOf(lotId).isEmpty()) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "This car park has no entrance sensor");
            }
            gateLot = gate;
            this.lotId = lotId;
            this.target = target;
            this.changesPerMinute = changesPerMinute;
            startedAt = clock.instant();
            endsAt = startedAt.plus(MAX_RUN);
            changes = 0;
            touched.clear();
            Duration period = Duration.ofMillis(60_000L / changesPerMinute);
            // delay first: the caller gets its answer before the first change
            task = scheduler.scheduleWithFixedDelay(this::safeTick, clock.instant().plus(period), period);
        }
    }

    /** @return the lot that was running, or null when nothing was */
    public Integer stop() {
        synchronized (lock) {
            return stopLocked();
        }
    }

    @PreDestroy
    void shutdown() {
        Integer lot = stop();
        if (lot != null) {
            AUDIT.info("stop traffic by sub=system username=system lot={} reason=shutdown", lot);
        }
    }

    public TrafficStatusDto status() {
        synchronized (lock) {
            return task == null ? new TrafficStatusDto(false, null, null, null, null, null, changes)
                    : new TrafficStatusDto(true, lotId, target, changesPerMinute, startedAt, endsAt, changes);
        }
    }

    // a task that throws is never run again: keep it alive across a database hiccup
    private void safeTick() {
        try {
            tick();
        } catch (RuntimeException e) {
            AUDIT.warn("traffic tick failed: {}", e.toString());
        }
    }

    /** One change, or the auto-stop once the 60 minutes are over. Public so the tests can drive it without waiting. */
    public void tick() {
        synchronized (lock) {
            if (task == null) {
                return;
            }
            if (!clock.instant().isBefore(endsAt)) {
                int lot = lotId;
                stopLocked();
                AUDIT.info("stop traffic by sub=system username=system lot={} reason=auto-stop", lot);
                return;
            }
            if (gateLot) {
                gateTick();
                return;
            }
            List<SensorSpot> spots = jdbc.query(SENSOR_SPOTS + " AND l.parking_lot_id = ? ORDER BY sp.parking_spot_id",
                    (rs, i) -> new SensorSpot(rs.getInt(1), rs.getInt(2), rs.getBoolean(3)), lotId);
            if (spots.isEmpty()) {
                return;
            }
            double fraction = spots.stream().filter(SensorSpot::occupied).count() / (double) spots.size();
            boolean occupy = (fraction < target) ^ (random.nextDouble() < NOISE);
            List<SensorSpot> candidates = new ArrayList<>(spots.stream().filter(s -> s.occupied() != occupy).toList());
            if (candidates.isEmpty()) {
                return; // every spot is already in that state
            }
            SensorSpot pick = candidates.get(random.nextInt(candidates.size()));
            measurements.record(pick.sensorId(), occupy, true);
            touched.putIfAbsent(pick.spotId(), pick.occupied());
            changes++;
        }
    }

    // one gate reading (+1 or -1) towards the target: cars inside / spots in the lot, same noise as the spot version
    private void gateTick() {
        long total = jdbc.queryForObject("SELECT count(*) FROM parking_spots sp JOIN parking_rows r ON r.parking_row_id = sp.parking_row_id "
                + "WHERE r.parking_lot_id = ? AND sp.archived_at IS NULL AND r.archived_at IS NULL", Long.class, lotId);
        long inside = jdbc.queryForObject("SELECT GREATEST(0, COALESCE(SUM(m.measured_value), 0)) FROM entrance_measurements m "
                + "JOIN entrance_sensors s ON s.entrance_sensor_id = m.entrance_sensor_id WHERE s.parking_lot_id = ?", Long.class, lotId);
        List<Integer> gate = gatesOf(lotId);
        boolean in = (inside < target * total) ^ (random.nextDouble() < NOISE);
        if (gate.isEmpty() || (in ? inside >= total : inside == 0)) {
            return; // nothing to do, or already full / empty
        }
        gateReadings.record(gate.get(0), in ? 1 : -1, true);
        changes++;
    }

    // each touched spot goes back to a real reading made during the run, else to what it was when first touched: a direct update, no new history row
    private Integer stopLocked() {
        if (task == null) {
            return null;
        }
        task.cancel(false);
        task = null;
        if (gateLot) { // the run's simulated gate readings go, so the count is what it was
            jdbc.update("DELETE FROM entrance_measurements WHERE simulated AND measured_at >= ? AND entrance_sensor_id IN "
                    + "(SELECT entrance_sensor_id FROM entrance_sensors WHERE parking_lot_id = ?)", Timestamp.from(startedAt), lotId);
        }
        for (var e : touched.entrySet()) {
            jdbc.update("""
                    UPDATE parking_spots sp SET updated_at = now(), occupied = coalesce((
                        SELECT m.occupied FROM parking_spot_measurements m
                        JOIN parking_spot_sensors s ON s.parking_spot_sensor_id = m.parking_spot_sensor_id
                        WHERE s.parking_spot_id = sp.parking_spot_id AND NOT m.simulated AND m.measured_at >= ?
                        ORDER BY m.measured_at DESC, m.parking_spot_measurement_id DESC LIMIT 1), ?)
                    WHERE sp.parking_spot_id = ?""", Timestamp.from(startedAt), e.getValue(), e.getKey());
        }
        touched.clear();
        return lotId;
    }
}
