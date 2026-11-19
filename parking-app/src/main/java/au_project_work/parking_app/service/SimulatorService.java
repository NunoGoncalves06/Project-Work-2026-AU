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
    private final TaskScheduler scheduler;
    private final Clock clock;
    private final RandomGenerator random;

    private final Object lock = new Object();
    // all below guarded by lock; task == null means nothing runs
    private ScheduledFuture<?> task;
    private int lotId;
    private double target;
    private int changesPerMinute;
    private Instant startedAt;
    private Instant endsAt;
    private int changes;
    // spot -> its occupied value when the simulation first changed it
    private final Map<Integer, Boolean> touched = new HashMap<>();

    public SimulatorService(JdbcTemplate jdbc, ParkingSpotMeasurementService measurements, TaskScheduler scheduler,
            Clock clock, RandomGenerator random) {
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

    /** @throws ResponseStatusException 404 for an unknown or archived lot, 409 when a simulation is running */
    public void start(int lotId, double target, int changesPerMinute) {
        synchronized (lock) {
            if (task != null) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "Already running");
            }
            if (!Boolean.TRUE.equals(jdbc.queryForObject(
                    "SELECT EXISTS (SELECT 1 FROM parking_lots WHERE parking_lot_id = ? AND archived_at IS NULL)",
                    Boolean.class, lotId))) {
                throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Not found");
            }
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

    // each touched spot goes back to a real reading made during the run, else to what it was when first touched: a direct update, no new history row
    private Integer stopLocked() {
        if (task == null) {
            return null;
        }
        task.cancel(false);
        task = null;
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
