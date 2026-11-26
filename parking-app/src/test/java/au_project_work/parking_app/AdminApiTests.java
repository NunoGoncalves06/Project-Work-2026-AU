package au_project_work.parking_app;

import au_project_work.parking_app.repository.ParkingLotRepository;
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
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
@ExtendWith(OutputCaptureExtension.class)
class AdminApiTests {

    private static final String LOT = "{\"street\":\"Test St\",\"houseNr\":\"1\",\"city\":\"Aarhus\",\"latitude\":56.1,\"longitude\":10.2}";

    @Autowired
    private MockMvc mvc;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private ParkingLotRepository parkingLotRepository;

    private int lotsBefore;

    // the seeded data stays as it was: every lot made here is archived afterwards
    @BeforeEach
    void remember() {
        lotsBefore = jdbc.queryForObject("SELECT coalesce(max(parking_lot_id), 0) FROM parking_lots", Integer.class);
    }

    @AfterEach
    void cleanUp() {
        jdbc.update("UPDATE parking_lots SET archived_at = now() WHERE parking_lot_id > ? AND archived_at IS NULL", lotsBefore);
        jdbc.update("DELETE FROM entrance_measurements WHERE entrance_sensor_id BETWEEN 9160 AND 9169");
    }

    private static RequestPostProcessor as(String role) {
        return jwt().jwt(j -> j.subject("sub-1").claim("username", "ana"))
                .authorities(new SimpleGrantedAuthority("ROLE_" + role));
    }

    private int create(String path, String body) throws Exception {
        String json = mvc.perform(post(path).with(as("ADMIN")).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return Integer.parseInt(json.replaceAll("\\D", ""));
    }

    private ResultActions postExpect(String path, String body, int sensorStatus) throws Exception {
        return mvc.perform(post(path).with(as("ADMIN")).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().is(sensorStatus));
    }

    private static String sensor(int id) {
        return "{\"sensorId\":" + id + "}";
    }

    @Test
    void createdLotRowSpotShowInThePublicApiAndTheFingerprintMoves() throws Exception {
        String before = parkingLotRepository.fingerprint();
        int lot = create("/api/admin/lots", LOT);
        assertNotEquals(before, parkingLotRepository.fingerprint()); // a new lot with no spot yet is noticed
        int row = create("/api/admin/lots/" + lot + "/rows", "");
        int spot = create("/api/admin/rows/" + row + "/spots", sensor(9101));
        create("/api/admin/lots/" + lot + "/entrance-sensors", sensor(9102));

        mvc.perform(get("/parking-lots/" + lot))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalSpots").value(1))
                .andExpect(jsonPath("$.rows[0].id").value(row))
                .andExpect(jsonPath("$.rows[0].spots[0].id").value(spot));
        mvc.perform(get("/api/admin/lots").with(as("VIEWER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.id == " + lot + ")].rows[0].spots[0].sensorId").value(9101))
                .andExpect(jsonPath("$[?(@.id == " + lot + ")].entranceSensorIds[0]").value(9102))
                .andExpect(jsonPath("$[?(@.id == " + lot + ")].carsInside").value(0)); // a new gate has counted nobody yet
        // the dashboard shows the same cars-inside count as the app's public route
        long inside = jdbc.queryForObject("""
                SELECT GREATEST(0, COALESCE(SUM(m.measured_value), 0)) FROM entrance_measurements m
                JOIN entrance_sensors s ON s.entrance_sensor_id = m.entrance_sensor_id WHERE s.parking_lot_id = 1""", Long.class);
        mvc.perform(get("/api/admin/lots").with(as("VIEWER")))
                .andExpect(jsonPath("$[?(@.id == 1)].carsInside").value((int) inside));
        mvc.perform(get("/parking-lots/1")).andExpect(jsonPath("$.carsInside").value((int) inside));
    }

    @Test
    void sensorOfAnActiveSpotIs409AndSensorOfAnArchivedSpotIsMoved() throws Exception {
        int lot = create("/api/admin/lots", LOT);
        int row = create("/api/admin/lots/" + lot + "/rows", "");
        int first = create("/api/admin/rows/" + row + "/spots", sensor(9111));
        postExpect("/api/admin/rows/" + row + "/spots", sensor(9111), 409);
        postExpect("/api/admin/lots/" + lot + "/entrance-sensors", sensor(9112), 201);
        postExpect("/api/admin/lots/" + lot + "/entrance-sensors", sensor(9112), 409);

        mvc.perform(delete("/api/admin/spots/" + first).with(as("ADMIN"))).andExpect(status().isNoContent());
        mvc.perform(delete("/api/admin/spots/" + first).with(as("ADMIN"))).andExpect(status().isNotFound());
        int second = create("/api/admin/rows/" + row + "/spots", sensor(9111));
        assertEquals(second, jdbc.queryForObject(
                "SELECT parking_spot_id FROM parking_spot_sensors WHERE parking_spot_sensor_id = 9111", Integer.class));
    }

    @Test
    void aSpotWithoutASensorCountsAndCanGetASensorLater() throws Exception {
        int lot = create("/api/admin/lots", LOT);
        int row = create("/api/admin/lots/" + lot + "/rows", "");
        int bare = create("/api/admin/rows/" + row + "/spots", "{}"); // a lot counted at the entrance needs no spot sensors
        postExpect("/api/admin/rows/" + row + "/spots", "{\"sensorId\":0}", 400);
        mvc.perform(get("/parking-lots/" + lot)).andExpect(jsonPath("$.totalSpots").value(1));
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM parking_spot_sensors WHERE parking_spot_id = ?",
                Integer.class, bare));

        // switching to a sensor on every spot later: a parked sensor is moved onto the bare spot
        int other = create("/api/admin/rows/" + row + "/spots", sensor(9171));
        mvc.perform(delete("/api/admin/spots/" + other).with(as("ADMIN"))).andExpect(status().isNoContent());
        move(9171, bare, 200);
        assertEquals(bare, sensorSpot(9171));
    }

    private ResultActions move(int sensor, int spot, int expected) throws Exception {
        return mvc.perform(put("/api/admin/sensors/" + sensor + "/spot").with(as("ADMIN"))
                .contentType(MediaType.APPLICATION_JSON).content("{\"spotId\":" + spot + "}"))
                .andExpect(status().is(expected));
    }

    private int sensorSpot(int sensor) {
        return jdbc.queryForObject("SELECT parking_spot_id FROM parking_spot_sensors WHERE parking_spot_sensor_id = ?",
                Integer.class, sensor);
    }

    @Test
    void moveSensor() throws Exception {
        int lot = create("/api/admin/lots", LOT);
        int row = create("/api/admin/lots/" + lot + "/rows", "");
        int spot1 = create("/api/admin/rows/" + row + "/spots", sensor(9121));
        int other = create("/api/admin/rows/" + row + "/spots", sensor(9122));
        // an occupied sensor-less target spot, made directly (the API makes them free)
        int free = jdbc.queryForObject("INSERT INTO parking_spots (parking_row_id, occupied) VALUES (?, true) RETURNING parking_spot_id",
                Integer.class, row);
        move(9999, free, 404);
        move(9121, 999999, 404);
        move(9121, spot1, 200); // same spot: nothing to do
        move(9121, free, 409); // its spot is still active: archive it first
        mvc.perform(delete("/api/admin/spots/" + spot1).with(as("ADMIN"))).andExpect(status().isNoContent());
        mvc.perform(get("/api/admin/sensors/parked").with(as("VIEWER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.sensorId == 9121)].spotId").value(spot1));
        move(9121, other, 409); // target has a sensor
        move(9121, free, 200);
        assertEquals(free, sensorSpot(9121));
        assertEquals(false, jdbc.queryForObject("SELECT occupied FROM parking_spots WHERE parking_spot_id = ?", Boolean.class, free));
        // archived target
        mvc.perform(delete("/api/admin/spots/" + other).with(as("ADMIN"))).andExpect(status().isNoContent());
        move(9122, other, 404);
        mvc.perform(delete("/api/admin/rows/" + row).with(as("ADMIN"))).andExpect(status().isNoContent());
        postExpect("/api/admin/lots/" + lot + "/entrance-sensors", sensor(9123), 201);
        mvc.perform(delete("/api/admin/lots/" + lot).with(as("ADMIN"))).andExpect(status().isNoContent());
        postExpect("/api/admin/lots/" + lot + "/entrance-sensors", sensor(9124), 404);
    }

    @Test
    void twoParallelCallsForASensorOnAnArchivedSpotGiveOne201AndOne409() throws Exception {
        int lot = create("/api/admin/lots", LOT);
        int row = create("/api/admin/lots/" + lot + "/rows", "");
        int spot = create("/api/admin/rows/" + row + "/spots", sensor(9141));
        mvc.perform(delete("/api/admin/spots/" + spot).with(as("ADMIN"))).andExpect(status().isNoContent());
        java.util.concurrent.CountDownLatch go = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.Callable<Integer> call = () -> {
            go.await();
            return mvc.perform(post("/api/admin/rows/" + row + "/spots").with(as("ADMIN"))
                    .contentType(MediaType.APPLICATION_JSON).content(sensor(9141))).andReturn().getResponse().getStatus();
        };
        java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(2);
        try {
            var a = pool.submit(call);
            var b = pool.submit(call);
            go.countDown();
            assertEquals(java.util.List.of(201, 409), java.util.stream.Stream.of(a.get(30, java.util.concurrent.TimeUnit.SECONDS),
                    b.get(30, java.util.concurrent.TimeUnit.SECONDS)).sorted().toList());
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void movedSensorOnAddSpotMakesTheNewSpotFree() throws Exception {
        int lot = create("/api/admin/lots", LOT);
        int row = create("/api/admin/lots/" + lot + "/rows", "");
        int spot = create("/api/admin/rows/" + row + "/spots", sensor(9151));
        jdbc.update("UPDATE parking_spots SET occupied = true WHERE parking_spot_id = ?", spot);
        mvc.perform(delete("/api/admin/spots/" + spot).with(as("ADMIN"))).andExpect(status().isNoContent());
        int next = create("/api/admin/rows/" + row + "/spots", sensor(9151));
        assertEquals(false, jdbc.queryForObject("SELECT occupied FROM parking_spots WHERE parking_spot_id = ?", Boolean.class, next));
    }

    @Test
    void controlCharactersInTheAddressAre400() throws Exception {
        postExpect("/api/admin/lots", LOT.replace("Test St", "Test\nSt"), 400);
        postExpect("/api/admin/lots", LOT.replace("Aarhus", "Aar\u0000hus"), 400);
    }

    @Test
    void auditLinesHaveNoLineBreaksFromTheUsername(CapturedOutput output) throws Exception {
        int lot = create("/api/admin/lots", LOT);
        mvc.perform(delete("/api/admin/lots/" + lot)
                .with(jwt().jwt(j -> j.subject("s\nfake").claim("username", "an\r\nINFO forged"))
                        .authorities(new SimpleGrantedAuthority("ROLE_ADMIN"))))
                .andExpect(status().isNoContent());
        String log = output.getAll();
        assertTrue(log.contains("archive lot by sub=s_fake username=an__INFO forged lot=" + lot), log);
    }

    @Test
    void withoutLoginTheNewRoutesAreRefused() throws Exception {
        // the test context has no login configured: shut (503), never open
        mvc.perform(post("/api/admin/lots").contentType(MediaType.APPLICATION_JSON).content(LOT)).andExpect(status().isServiceUnavailable());
        mvc.perform(put("/api/admin/sensors/1/spot").contentType(MediaType.APPLICATION_JSON).content("{\"spotId\":1}")).andExpect(status().isServiceUnavailable());
        mvc.perform(delete("/api/admin/spots/1")).andExpect(status().isServiceUnavailable());
    }

    @Test
    void archivedSpotRowAndLotDisappearAndTheFingerprintChanges() throws Exception {
        int lot = create("/api/admin/lots", LOT);
        int row = create("/api/admin/lots/" + lot + "/rows", "");
        int spot = create("/api/admin/rows/" + row + "/spots", sensor(9131));
        int row2 = create("/api/admin/lots/" + lot + "/rows", "");
        create("/api/admin/rows/" + row2 + "/spots", sensor(9132));

        String f = parkingLotRepository.fingerprint();
        mvc.perform(delete("/api/admin/spots/" + spot).with(as("ADMIN"))).andExpect(status().isNoContent());
        assertNotEquals(f, parkingLotRepository.fingerprint());
        mvc.perform(get("/parking-lots/" + lot)).andExpect(jsonPath("$.totalSpots").value(1));

        f = parkingLotRepository.fingerprint();
        mvc.perform(delete("/api/admin/rows/" + row2).with(as("ADMIN"))).andExpect(status().isNoContent());
        assertNotEquals(f, parkingLotRepository.fingerprint());
        mvc.perform(get("/parking-lots/" + lot)).andExpect(jsonPath("$.totalSpots").value(0));
        // a row of an archived lot counts as missing
        mvc.perform(delete("/api/admin/lots/" + lot).with(as("ADMIN"))).andExpect(status().isNoContent());
        mvc.perform(get("/parking-lots/" + lot)).andExpect(status().isNotFound());
        postExpect("/api/admin/lots/" + lot + "/rows", "", 404);
        postExpect("/api/admin/rows/" + row + "/spots", sensor(9133), 404);
        mvc.perform(delete("/api/admin/lots/" + lot).with(as("ADMIN"))).andExpect(status().isNotFound());
    }

    @Test
    void onlyAdminsMayChange() throws Exception {
        for (String role : new String[] {"VIEWER", "OPERATOR"}) {
            mvc.perform(post("/api/admin/lots").with(as(role)).contentType(MediaType.APPLICATION_JSON).content(LOT))
                    .andExpect(status().isForbidden());
            mvc.perform(delete("/api/admin/spots/1").with(as(role))).andExpect(status().isForbidden());
            mvc.perform(get("/api/admin/lots").with(as(role))).andExpect(status().isOk());
        }
        assertEquals(lotsBefore, (int) jdbc.queryForObject("SELECT max(parking_lot_id) FROM parking_lots", Integer.class));
    }

    @Test
    void badInputIs400() throws Exception {
        for (String body : new String[] {LOT.replace("56.1", "91"), LOT.replace("10.2", "-181"),
                LOT.replace("Test St", ""), LOT.replace("Aarhus", "x".repeat(51)), "{}", "not json",
                LOT.replace("}", ",\"extra\":1}")}) {
            postExpect("/api/admin/lots", body, 400).andExpect(jsonPath("$.error").value("Invalid input"));
        }
        int lot = create("/api/admin/lots", LOT);
        postExpect("/api/admin/lots/" + lot + "/entrance-sensors", "{\"sensorId\":0}", 400);
        postExpect("/api/admin/lots/" + lot + "/entrance-sensors", "{\"sensorId\":1,\"x\":2}", 400);
    }

    @Test
    void everyChangeWritesOneAuditLine(CapturedOutput output) throws Exception {
        int lot = create("/api/admin/lots", LOT);
        String log = output.getAll();
        assertTrue(log.contains("create lot by sub=sub-1 username=ana lot=" + lot), log);
        assertTrue(!log.contains("Test St"), "free text must not be logged");
    }
    private void gateReading(int sensor, int value) {
        jdbc.update("INSERT INTO entrance_measurements (entrance_sensor_id, measured_value, measured_at) VALUES (?, ?, now())", sensor, value);
    }

    @Test
    void entranceLotShowsCapacityMinusCarsInsideAndNoForecast() throws Exception {
        int lot = create("/api/admin/lots", LOT.replace("}", ",\"countedBy\":\"entrance\"}"));
        int row = create("/api/admin/lots/" + lot + "/rows", "");
        for (int sensor = 9161; sensor <= 9163; sensor++) {
            create("/api/admin/rows/" + row + "/spots", sensor(sensor)); // spot states are not used for this lot
        }
        create("/api/admin/lots/" + lot + "/entrance-sensors", sensor(9164));
        gateReading(9164, 1);
        gateReading(9164, 1);
        mvc.perform(get("/parking-lots/" + lot))
                .andExpect(jsonPath("$.countedBy").value("entrance"))
                .andExpect(jsonPath("$.totalSpots").value(3))
                .andExpect(jsonPath("$.freeSpots").value(1))
                .andExpect(jsonPath("$.carsInside").value(2))
                .andExpect(jsonPath("$.rows[0].spots.length()").value(3))
                .andExpect(jsonPath("$.forecast").isEmpty());
        mvc.perform(get("/parking-lots")).andExpect(jsonPath("$[?(@.id == " + lot + ")].freeSpots").value(1))
                .andExpect(jsonPath("$[?(@.id == " + lot + ")].countedBy").value("entrance"));
        mvc.perform(get("/api/admin/lots").with(as("VIEWER"))).andExpect(jsonPath("$[?(@.id == " + lot + ")].countedBy").value("entrance"));
        gateReading(9164, 1);
        gateReading(9164, 1); // 4 cars in a lot of 3: free stays at 0
        mvc.perform(get("/parking-lots/" + lot)).andExpect(jsonPath("$.freeSpots").value(0));
        mvc.perform(get("/parking-lots/1")).andExpect(jsonPath("$.countedBy").value("spots")); // the seeded lot is unchanged
    }

    @Test
    void countingCanBeChangedByAdminOnly() throws Exception {
        int lot = create("/api/admin/lots", LOT);
        create("/api/admin/lots/" + lot + "/rows", "");
        String body = "{\"countedBy\":\"entrance\"}";
        String path = "/api/admin/lots/" + lot + "/counting";
        for (String role : new String[] {"VIEWER", "OPERATOR"}) {
            mvc.perform(put(path).with(as(role)).contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isForbidden());
        }
        mvc.perform(put(path).with(as("ADMIN")).contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isNoContent());
        mvc.perform(get("/parking-lots/" + lot)).andExpect(jsonPath("$.countedBy").value("entrance"));
        mvc.perform(put(path).with(as("ADMIN")).contentType(MediaType.APPLICATION_JSON).content("{\"countedBy\":\"spots\"}"))
                .andExpect(status().isNoContent());
        mvc.perform(get("/parking-lots/" + lot)).andExpect(jsonPath("$.countedBy").value("spots"));
        for (String bad : new String[] {"{\"countedBy\":\"gate\"}", "{}", "{\"countedBy\":null}"}) {
            mvc.perform(put(path).with(as("ADMIN")).contentType(MediaType.APPLICATION_JSON).content(bad)).andExpect(status().isBadRequest());
        }
        mvc.perform(put("/api/admin/lots/999999/counting").with(as("ADMIN")).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isNotFound());
        mvc.perform(post("/api/admin/lots").with(as("ADMIN")).contentType(MediaType.APPLICATION_JSON)
                .content(LOT.replace("}", ",\"countedBy\":\"gate\"}"))).andExpect(status().isBadRequest());
    }
}
