package au_project_work.parking_app;

import au_project_work.parking_app.api.Dtos.CreateLotRequest;
import au_project_work.parking_app.api.ForecastCache;
import au_project_work.parking_app.repository.EntranceMeasurementRepository;
import au_project_work.parking_app.repository.ParkingLotRepository;
import au_project_work.parking_app.service.AdminService;
import jakarta.servlet.DispatcherType;
import jakarta.servlet.RequestDispatcher;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.integration.mqtt.support.MqttHeaders;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.integration.support.MessageBuilder;
import org.springframework.messaging.MessageChannel;
import org.springframework.test.web.servlet.MockMvc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.head;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class ParkingAppApplicationTests {

    @Autowired
    private MockMvc mvc;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private EntranceMeasurementRepository entranceMeasurementRepository;

    @Autowired
    private ParkingLotRepository parkingLotRepository;

    @Autowired
    private ForecastCache forecastCache;

    @Autowired
    private AdminService adminService;

    @Autowired
    @Qualifier("mqttInputChannel")
    private MessageChannel mqttInputChannel;

    // what the MQTT adapter does with a broker message: router, listener and service all run
    private void publish(String topic, String payload) {
        mqttInputChannel.send(MessageBuilder.withPayload(payload)
                .setHeader(MqttHeaders.RECEIVED_TOPIC, topic).build());
    }

    @Test
    void contextLoads() {
    }

    @Test
    void appRoutesNeedNoLogin() throws Exception {
        for (String path : List.of("/parking-lots", "/parking-lots/1", "/parking-lots/stream",
                "/api/health", "/api/auth/config")) {
            mvc.perform(get(path)).andExpect(status().isOk());
        }
        mvc.perform(head("/parking-lots")).andExpect(status().isOk());
    }

    // no COGNITO_* and no ADMIN_AUTH here, as on a server before the login is set up: shut, never open
    @Test
    void withoutLoginConfigAdminAndUnlistedRoutesAreRefused() throws Exception {
        mvc.perform(get("/api/auth/config")).andExpect(jsonPath("$.mode").value("unconfigured"));
        mvc.perform(get("/api/admin/me")).andExpect(status().isServiceUnavailable());
        mvc.perform(get("/api/admin/me").header("Authorization", "Bearer a.b.c")).andExpect(status().isServiceUnavailable());
        mvc.perform(post("/api/admin/lots")).andExpect(status().isServiceUnavailable());
        mvc.perform(get("/nope")).andExpect(status().isServiceUnavailable());
        mvc.perform(post("/parking-lots")).andExpect(status().isServiceUnavailable());
    }

    // Tomcat forwards a 404 (unknown lot) to /error: that must stay a 404 for the app, not become a refusal
    @Test
    void errorPageIsNotRefused() throws Exception {
        mvc.perform(get("/error").with(request -> {
                    request.setDispatcherType(DispatcherType.ERROR);
                    request.setAttribute(RequestDispatcher.ERROR_STATUS_CODE, 404);
                    return request;
                }))
                .andExpect(status().isNotFound());
        mvc.perform(get("/error")).andExpect(status().isServiceUnavailable());
    }

    @Test
    void listsLotsWithSeedNumbers() throws Exception {
        mvc.perform(get("/parking-lots"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].id").value(1))
                .andExpect(jsonPath("$[0].address.street").value("Finlandsgade"))
                .andExpect(jsonPath("$[0].address.houseNr").value("22"))
                .andExpect(jsonPath("$[0].address.latitude").value(56.1716))
                .andExpect(jsonPath("$[0].address.city").value("Aarhus"))
                .andExpect(jsonPath("$[0].totalSpots").value(16))
                .andExpect(jsonPath("$[0].freeSpots").value(12))
                .andExpect(jsonPath("$[0].carsInside").value(4))
                .andExpect(jsonPath("$[0].rows").doesNotExist());
    }

    @Test
    void lotDetailHasRowsAndSpots() throws Exception {
        mvc.perform(get("/parking-lots/1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.freeSpots").value(12))
                .andExpect(jsonPath("$.rows.length()").value(4))
                .andExpect(jsonPath("$.rows[0].freeSpots").value(0))
                .andExpect(jsonPath("$.rows[0].spots.length()").value(3))
                .andExpect(jsonPath("$.rows[0].spots[0].id").value(1))
                .andExpect(jsonPath("$.rows[0].spots[0].occupied").value(true))
                .andExpect(jsonPath("$.rows[3].spots[3].id").value(16))
                .andExpect(jsonPath("$.rows[3].spots[3].occupied").value(false));
    }

    @Test
    void unknownLotIs404() throws Exception {
        mvc.perform(get("/parking-lots/99")).andExpect(status().isNotFound());
    }

    @Test
    void spotMessageUpdatesSpotState() throws Exception {
        publish("carcounter/spots", "{\"device_id\": 16, \"occupied\": true}");
        mvc.perform(get("/parking-lots/1"))
                .andExpect(jsonPath("$.rows[3].spots[3].occupied").value(true))
                .andExpect(jsonPath("$.freeSpots").value(11));

        publish("carcounter/spots", "{\"device_id\": 16, \"occupied\": false}");
        mvc.perform(get("/parking-lots/1"))
                .andExpect(jsonPath("$.freeSpots").value(12));
    }

    @Test
    void entranceMessageChangesCarsInside() throws Exception {
        publish("carcounter/entrance", "{\"device_id\": 1, \"dir\": 1}");
        mvc.perform(get("/parking-lots/1")).andExpect(jsonPath("$.carsInside").value(5));

        publish("carcounter/entrance", "{\"device_id\": 1, \"dir\": -1}");
        mvc.perform(get("/parking-lots/1")).andExpect(jsonPath("$.carsInside").value(4));
    }

    @Test
    void carsInsideResyncsToOccupiedSpotsAt4am() {
        // seed: spots 1-4 occupied (with history from October), so the 04:00 start value is 4.
        // A stray +1 from 2 days ago is drift that the resync must ignore
        Long drift = jdbc.queryForObject("""
                INSERT INTO entrance_measurements (entrance_sensor_id, measured_value, measured_at)
                VALUES (1, 1, now() - interval '2 days') RETURNING entrance_measurement_id
                """, Long.class);
        try {
            assertEquals(4, entranceMeasurementRepository.carsInsideResyncedAt4am(1));
            publish("carcounter/entrance", "{\"device_id\": 1, \"dir\": 1}");
            assertEquals(5, entranceMeasurementRepository.carsInsideResyncedAt4am(1));
            publish("carcounter/entrance", "{\"device_id\": 1, \"dir\": -1}");
            assertEquals(4, entranceMeasurementRepository.carsInsideResyncedAt4am(1));
        } finally {
            jdbc.update("DELETE FROM entrance_measurements WHERE entrance_measurement_id = ?", drift);
        }
    }

    @Test
    void forecastAveragesTheLastFourWeeks() throws Exception {
        forecastCache.clear(); // the forecast is cached per hour and this test changes the history in between
        // seed history (October) is older than all 4 sampled weeks: spots 1-4 taken in each
        mvc.perform(get("/parking-lots/1"))
                .andExpect(jsonPath("$.forecast.length()").value(org.hamcrest.Matchers.oneOf(167, 168))) // 167 in the spring-forward week (two hours merge)
                .andExpect(jsonPath("$.forecast[0].at").isString())
                .andExpect(jsonPath("$.forecast[0].freeSpots").value(12));

        // spots 15 and 16 taken since 15 days ago: for the next day's hours they are occupied in the
        // 2 newest sampled weeks only, so on average one more spot is taken
        jdbc.update("""
                INSERT INTO parking_spot_measurements (parking_spot_sensor_id, occupied, measured_at)
                VALUES (15, true, now() - interval '15 days'), (16, true, now() - interval '15 days')
                """);
        forecastCache.clear();
        try {
            mvc.perform(get("/parking-lots/1"))
                    .andExpect(jsonPath("$.forecast[0:24].freeSpots").value(org.hamcrest.Matchers.everyItem(
                            org.hamcrest.Matchers.is(11))));
            // the list endpoint stays lean
            mvc.perform(get("/parking-lots")).andExpect(jsonPath("$[0].forecast").doesNotExist());
        } finally {
            jdbc.update("DELETE FROM parking_spot_measurements WHERE measured_at > now() - interval '16 days'");
            forecastCache.clear();
        }
    }

    @Test
    void badOrUnknownSensorMessagesAreSkipped() throws Exception {
        publish("carcounter/spots", "{\"device_id\": 1}");
        publish("carcounter/spots", "{\"device_id\": 99, \"occupied\": true}");
        publish("carcounter/spots", "not json");
        publish("carcounter/entrance", "{\"device_id\": 1}");
        publish("carcounter/entrance", "{\"device_id\": 1, \"dir\": 5}");
        publish("carcounter/entrance", "{\"device_id\": 99, \"dir\": 1}");
        mvc.perform(get("/parking-lots/1"))
                .andExpect(jsonPath("$.freeSpots").value(12))
                .andExpect(jsonPath("$.carsInside").value(4));
    }

    @Test
    void archivedSpotsAndRowsDisappearFromTheApi() throws Exception {
        jdbc.update("UPDATE parking_spots SET archived_at = now() WHERE parking_spot_id = 16");
        try {
            mvc.perform(get("/parking-lots"))
                    .andExpect(jsonPath("$[0].totalSpots").value(15))
                    .andExpect(jsonPath("$[0].freeSpots").value(11));
            mvc.perform(get("/parking-lots/1"))
                    .andExpect(jsonPath("$.totalSpots").value(15))
                    .andExpect(jsonPath("$.rows[3].spots.length()").value(3));
        } finally {
            jdbc.update("UPDATE parking_spots SET archived_at = NULL WHERE parking_spot_id = 16");
        }

        jdbc.update("UPDATE parking_rows SET archived_at = now() WHERE parking_row_id = 4");
        try {
            mvc.perform(get("/parking-lots/1")).andExpect(jsonPath("$.rows.length()").value(3));
        } finally {
            jdbc.update("UPDATE parking_rows SET archived_at = NULL WHERE parking_row_id = 4");
        }
        mvc.perform(get("/parking-lots/1")).andExpect(jsonPath("$.rows.length()").value(4));
    }

    @Test
    void archivedLotIsHiddenFromTheApi() throws Exception {
        jdbc.update("UPDATE parking_lots SET archived_at = now() WHERE parking_lot_id = 1");
        try {
            mvc.perform(get("/parking-lots/1")).andExpect(status().isNotFound());
            mvc.perform(get("/parking-lots")).andExpect(jsonPath("$[?(@.parkingLotId == 1)]").isEmpty());
        } finally {
            jdbc.update("UPDATE parking_lots SET archived_at = NULL WHERE parking_lot_id = 1");
        }
    }

    @Test
    void readingForAnArchivedSpotIsStillStored() {
        int before = jdbc.queryForObject(
                "SELECT count(*) FROM parking_spot_measurements WHERE parking_spot_sensor_id = 16", Integer.class);
        jdbc.update("UPDATE parking_spots SET archived_at = now() WHERE parking_spot_id = 16");
        try {
            publish("carcounter/spots", "{\"device_id\": 16, \"occupied\": true}");
            assertEquals(before + 1, jdbc.queryForObject(
                    "SELECT count(*) FROM parking_spot_measurements WHERE parking_spot_sensor_id = 16", Integer.class));
        } finally {
            jdbc.update("UPDATE parking_spots SET archived_at = NULL WHERE parking_spot_id = 16");
            jdbc.update("DELETE FROM parking_spot_measurements WHERE parking_spot_sensor_id = 16 AND measured_at > now() - interval '1 minute'");
            jdbc.update("UPDATE parking_spots SET occupied = false WHERE parking_spot_id = 16");
        }
    }

    @Test
    void archivingChangesTheFingerprint() {
        String before = parkingLotRepository.fingerprint();
        jdbc.update("UPDATE parking_spots SET archived_at = now() WHERE parking_spot_id = 16");
        try {
            assertNotEquals(before, parkingLotRepository.fingerprint());
        } finally {
            jdbc.update("UPDATE parking_spots SET archived_at = NULL WHERE parking_spot_id = 16");
        }
        assertEquals(before, parkingLotRepository.fingerprint());
    }

    @Test
    void archivedSpotsAreLeftOutOfTheForecast() {
        // spot 16 is free for the whole next day in the seed: archiving it must lower the free count
        int before = parkingLotRepository.forecast(1).get(0).getFreeSpots();
        jdbc.update("UPDATE parking_spots SET archived_at = now() WHERE parking_spot_id = 16");
        try {
            assertEquals(before - 1, parkingLotRepository.forecast(1).get(0).getFreeSpots());
        } finally {
            jdbc.update("UPDATE parking_spots SET archived_at = NULL WHERE parking_spot_id = 16");
        }
    }

    @Test
    void simulatedMeasurementsDoNotChangeTheForecast() {
        List<Integer> before = parkingLotRepository.forecast(1).stream().map(h -> h.getFreeSpots()).toList();
        jdbc.update("""
                INSERT INTO parking_spot_measurements (parking_spot_sensor_id, occupied, measured_at, simulated)
                VALUES (15, true, now() - interval '15 days', true), (16, true, now() - interval '15 days', true)
                """);
        try {
            assertEquals(before, parkingLotRepository.forecast(1).stream().map(h -> h.getFreeSpots()).toList());
        } finally {
            jdbc.update("DELETE FROM parking_spot_measurements WHERE simulated");
        }
    }

    @Test
    void sensorMovedToAnotherLotLeavesItsOldHistoryBehind() {
        // sensor 16 has history in lot 1; its spot is archived and the sensor is attached to a spot of a new lot
        int lot = adminService.createLot(new CreateLotRequest("Teststreet", "1", "Aarhus", 56.0, 10.0, null));
        int row = adminService.addRow(lot);
        adminService.archiveSpot(16);
        int spot = adminService.addSpot(row, 16);
        try {
            assertEquals(List.of(), parkingLotRepository.forecast(lot));
            assertNotEquals(0, parkingLotRepository.forecast(1).size()); // lot 1 keeps its other spots' forecast
        } finally {
            jdbc.update("UPDATE parking_spot_sensors SET parking_spot_id = 16, attached_at = NULL WHERE parking_spot_sensor_id = 16");
            jdbc.update("UPDATE parking_spots SET archived_at = NULL WHERE parking_spot_id = 16");
            jdbc.update("DELETE FROM parking_spots WHERE parking_spot_id = ?", spot);
            jdbc.update("DELETE FROM parking_rows WHERE parking_row_id = ?", row);
            jdbc.update("DELETE FROM parking_lots WHERE parking_lot_id = ?", lot);
            forecastCache.clear();
        }
    }
}
