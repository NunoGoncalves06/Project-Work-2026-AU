package au_project_work.parking_app;

import au_project_work.parking_app.service.ParkingSpotMeasurementService;
import au_project_work.parking_app.service.SimulatorService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Random;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
@ExtendWith(OutputCaptureExtension.class)
class SimulatorTests {

    private static final AtomicInteger SENSOR_IDS = new AtomicInteger(9300);
    private static final String TRAFFIC = "/api/admin/simulator/traffic";

    @Autowired
    private MockMvc mvc;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private SimulatorService sim; // the bean the routes use
    @Autowired
    private ParkingSpotMeasurementService measurements;
    @Autowired
    private TaskScheduler scheduler;

    private int lotsBefore;
    private int firstSensor;

    // the seeded data stays as it was: lots made here are archived, their test readings deleted
    @BeforeEach
    void remember() {
        lotsBefore = jdbc.queryForObject("SELECT coalesce(max(parking_lot_id), 0) FROM parking_lots", Integer.class);
        firstSensor = SENSOR_IDS.get();
    }

    @AfterEach
    void cleanUp() {
        sim.stop();
        jdbc.update("UPDATE parking_lots SET archived_at = now() WHERE parking_lot_id > ? AND archived_at IS NULL", lotsBefore);
        jdbc.update("DELETE FROM parking_spot_measurements WHERE parking_spot_sensor_id >= ?", firstSensor);
    }

    private static RequestPostProcessor as(String role) {
        return jwt().jwt(j -> j.subject("sub-1").claim("username", "ana")).authorities(new SimpleGrantedAuthority("ROLE_" + role));
    }

    private int newLot() {
        int address = jdbc.queryForObject("INSERT INTO addresses (city, street, house_nr, latitude, longitude) "
                + "VALUES ('Aarhus', 'Sim St', '1', 56.1, 10.2) RETURNING address_id", Integer.class);
        return jdbc.queryForObject("INSERT INTO parking_lots (address_id) VALUES (?) RETURNING parking_lot_id", Integer.class, address);
    }

    private int newRow(int lot) {
        return jdbc.queryForObject("INSERT INTO parking_rows (parking_lot_id) VALUES (?) RETURNING parking_row_id", Integer.class, lot);
    }

    // a spot with the next free sensor id; the sensor id is found with sensorOf
    private int newSpot(int row, boolean withSensor) {
        int spot = jdbc.queryForObject("INSERT INTO parking_spots (parking_row_id) VALUES (?) RETURNING parking_spot_id", Integer.class, row);
        if (withSensor) {
            jdbc.update("INSERT INTO parking_spot_sensors (parking_spot_sensor_id, parking_spot_id) VALUES (?, ?)",
                    SENSOR_IDS.getAndIncrement(), spot);
        }
        return spot;
    }

    private int sensorOf(int spot) {
        return jdbc.queryForObject("SELECT parking_spot_sensor_id FROM parking_spot_sensors WHERE parking_spot_id = ?", Integer.class, spot);
    }

    private boolean occupied(int spot) {
        return jdbc.queryForObject("SELECT occupied FROM parking_spots WHERE parking_spot_id = ?", Boolean.class, spot);
    }

    private int measurementCount() {
        return jdbc.queryForObject("SELECT count(*) FROM parking_spot_measurements WHERE parking_spot_sensor_id >= ?",
                Integer.class, firstSensor);
    }

    private static String traffic(int lot, String target, int perMinute) {
        return "{\"lotId\":" + lot + ",\"targetOccupancy\":" + target + ",\"changesPerMinute\":" + perMinute + "}";
    }

    private static class FakeClock extends Clock {
        Instant now = Instant.parse("2026-11-18T10:00:00Z");

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }

    @Test
    void spotToggleStoresASimulatedReadingAndChangesThePublicState(CapturedOutput output) throws Exception {
        int lot = newLot();
        int spot = newSpot(newRow(lot), true);
        mvc.perform(post("/api/admin/simulator/spots/" + spot).with(as("OPERATOR"))
                .contentType(MediaType.APPLICATION_JSON).content("{\"occupied\":true}")).andExpect(status().isOk());
        assertTrue(jdbc.queryForObject("SELECT simulated FROM parking_spot_measurements WHERE parking_spot_sensor_id = ?",
                Boolean.class, sensorOf(spot)));
        mvc.perform(get("/parking-lots/" + lot)).andExpect(jsonPath("$.rows[0].spots[0].occupied").value(true));
        assertTrue(output.getAll().contains("simulate spot by sub=sub-1 username=ana spot=" + spot + " occupied=true"));
    }

    @Test
    void spotWithoutSensorOrArchivedOrUnknownIs404() throws Exception {
        int row = newRow(newLot());
        int noSensor = newSpot(row, false);
        int archived = newSpot(row, true);
        jdbc.update("UPDATE parking_spots SET archived_at = now() WHERE parking_spot_id = ?", archived);
        for (int spot : new int[] {noSensor, archived, 999999}) {
            mvc.perform(post("/api/admin/simulator/spots/" + spot).with(as("OPERATOR"))
                    .contentType(MediaType.APPLICATION_JSON).content("{\"occupied\":true}")).andExpect(status().isNotFound());
        }
    }

    @Test
    void viewerIs403OperatorAndAdminMaySimulate() throws Exception {
        int lot = newLot();
        int spot = newSpot(newRow(lot), true);
        String on = "{\"occupied\":true}";
        mvc.perform(post("/api/admin/simulator/spots/" + spot).with(as("VIEWER")).contentType(MediaType.APPLICATION_JSON).content(on))
                .andExpect(status().isForbidden());
        mvc.perform(post(TRAFFIC).with(as("VIEWER")).contentType(MediaType.APPLICATION_JSON).content(traffic(lot, "0.5", 6)))
                .andExpect(status().isForbidden());
        mvc.perform(get(TRAFFIC).with(as("VIEWER"))).andExpect(status().isForbidden());
        mvc.perform(delete(TRAFFIC).with(as("VIEWER"))).andExpect(status().isForbidden());
        for (String role : new String[] {"OPERATOR", "ADMIN"}) {
            mvc.perform(post("/api/admin/simulator/spots/" + spot).with(as(role)).contentType(MediaType.APPLICATION_JSON).content(on))
                    .andExpect(status().isOk());
            mvc.perform(get(TRAFFIC).with(as(role))).andExpect(status().isOk()).andExpect(jsonPath("$.running").value(false));
        }
    }

    @Test
    void startStatusStopAndASecondStartIs409() throws Exception {
        int lot = newLot();
        newSpot(newRow(lot), true);
        mvc.perform(post(TRAFFIC).with(as("OPERATOR")).contentType(MediaType.APPLICATION_JSON).content(traffic(lot, "0.5", 6)))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.running").value(true));
        mvc.perform(post(TRAFFIC).with(as("OPERATOR")).contentType(MediaType.APPLICATION_JSON).content(traffic(lot, "0.5", 6)))
                .andExpect(status().isConflict());
        mvc.perform(get(TRAFFIC).with(as("OPERATOR")))
                .andExpect(jsonPath("$.running").value(true))
                .andExpect(jsonPath("$.lotId").value(lot))
                .andExpect(jsonPath("$.targetOccupancy").value(0.5))
                .andExpect(jsonPath("$.changesPerMinute").value(6))
                .andExpect(jsonPath("$.endsAt").isNotEmpty());
        mvc.perform(delete(TRAFFIC).with(as("OPERATOR"))).andExpect(status().isNoContent());
        mvc.perform(get(TRAFFIC).with(as("OPERATOR"))).andExpect(jsonPath("$.running").value(false));
    }

    @Test
    void badParametersAre400AndAnUnknownLotIs404() throws Exception {
        int lot = newLot();
        for (String body : new String[] {traffic(lot, "0.5", 0), traffic(lot, "0.5", 61), traffic(lot, "1.5", 6),
                traffic(lot, "-0.1", 6), "{}"}) {
            mvc.perform(post(TRAFFIC).with(as("OPERATOR")).contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isBadRequest());
        }
        mvc.perform(post(TRAFFIC).with(as("OPERATOR")).contentType(MediaType.APPLICATION_JSON).content(traffic(999999, "0.5", 6)))
                .andExpect(status().isNotFound());
        assertFalse(sim.status().running());
    }

    @Test
    void ticksMoveOccupancyTowardsTheTargetAndAreNoOpsAfterStop() {
        int row = newRow(newLot());
        int lot = jdbc.queryForObject("SELECT parking_lot_id FROM parking_rows WHERE parking_row_id = ?", Integer.class, row);
        int[] spots = new int[10];
        for (int i = 0; i < spots.length; i++) {
            spots[i] = newSpot(row, true);
        }
        SimulatorService s = new SimulatorService(jdbc, measurements, scheduler, new FakeClock(), new Random(42));
        s.start(lot, 0.5, 1); // a tick every 60 s: never fires inside this test, the ticks below are driven by hand
        for (int i = 0; i < 40; i++) {
            s.tick();
        }
        long taken = java.util.Arrays.stream(spots).filter(this::occupied).count();
        assertTrue(taken >= 2 && taken <= 8, "occupied " + taken + " of 10");
        assertTrue(s.status().changes() > 20, "changes " + s.status().changes()); // a tick with nothing to flip is a no-op
        s.stop();
        int rows = measurementCount();
        s.tick();
        assertEquals(rows, measurementCount());
        assertFalse(s.status().running());
    }

    @Test
    void autoStopsAfter60MinutesAndWritesAnAuditLine(CapturedOutput output) {
        int row = newRow(newLot());
        int lot = jdbc.queryForObject("SELECT parking_lot_id FROM parking_rows WHERE parking_row_id = ?", Integer.class, row);
        newSpot(row, true);
        FakeClock clock = new FakeClock();
        SimulatorService s = new SimulatorService(jdbc, measurements, scheduler, clock, new Random(1));
        s.start(lot, 1.0, 1);
        clock.now = clock.now.plus(Duration.ofMinutes(59));
        s.tick();
        assertTrue(s.status().running());
        clock.now = clock.now.plus(Duration.ofMinutes(1));
        s.tick();
        assertFalse(s.status().running());
        assertTrue(output.getAll().contains("stop traffic by sub=system username=system lot=" + lot + " reason=auto-stop"));
    }

    @Test
    void stopPutsTouchedSpotsBackToTheirLastRealReading() {
        int row = newRow(newLot());
        int lot = jdbc.queryForObject("SELECT parking_lot_id FROM parking_rows WHERE parking_row_id = ?", Integer.class, row);
        int real = newSpot(row, true); // last real reading: taken
        int none = newSpot(row, true); // no real reading: free
        measurements.record(sensorOf(real), true);
        FakeClock clock = new FakeClock();
        SimulatorService s = new SimulatorService(jdbc, measurements, scheduler, clock, new Random(7));
        s.start(lot, 0.0, 60);
        for (int i = 0; i < 30; i++) {
            s.tick();
        }
        assertTrue(s.status().changes() > 0);
        int rows = measurementCount();
        s.stop();
        assertEquals(rows, measurementCount()); // restoring adds no history row
        assertTrue(occupied(real));
        assertFalse(occupied(none));
    }

    // runs the sim on a one-spot lot until the spot was touched, then stops
    private void runAndStop(int lot, int spot, Instant startsAt, Runnable duringRun) {
        FakeClock clock = new FakeClock();
        clock.now = startsAt;
        SimulatorService s = new SimulatorService(jdbc, measurements, scheduler, clock, new Random(3));
        s.start(lot, 1.0, 60);
        for (int i = 0; i < 30; i++) {
            s.tick();
        }
        assertTrue(s.status().changes() > 0);
        duringRun.run();
        s.stop();
    }

    @Test
    void stopRestoresTheValueFromBeforeTheRunNotAnOlderReading() {
        int row = newRow(newLot());
        int lot = jdbc.queryForObject("SELECT parking_lot_id FROM parking_rows WHERE parking_row_id = ?", Integer.class, row);
        int spot = newSpot(row, true);
        measurements.record(sensorOf(spot), true); // a real reading from before the run (the sensor's old spot, say)
        jdbc.update("UPDATE parking_spots SET occupied = false WHERE parking_spot_id = ?", spot); // the spot itself was free
        runAndStop(lot, spot, Instant.now().plusSeconds(3600), () -> { });
        assertFalse(occupied(spot));
    }

    @Test
    void stopKeepsARealReadingMadeDuringTheRun() {
        int row = newRow(newLot());
        int lot = jdbc.queryForObject("SELECT parking_lot_id FROM parking_rows WHERE parking_row_id = ?", Integer.class, row);
        int spot = newSpot(row, true);
        runAndStop(lot, spot, Instant.now().minusSeconds(3600), () -> measurements.record(sensorOf(spot), true));
        assertTrue(occupied(spot));
    }
}
