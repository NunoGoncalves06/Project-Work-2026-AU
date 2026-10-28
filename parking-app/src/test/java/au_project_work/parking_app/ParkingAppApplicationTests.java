package au_project_work.parking_app;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class ParkingAppApplicationTests {

    @Autowired
    private MockMvc mvc;

    private ResultActions postJson(String url, String body) throws Exception {
        return mvc.perform(post(url).contentType(MediaType.APPLICATION_JSON).content(body));
    }

    @Test
    void contextLoads() {
    }

    @Test
    void entranceMeasurementsUseCamelCaseKeys() throws Exception {
        mvc.perform(get("/entrance-measurements/sensor/1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].entranceMeasurementId").value(1))
                .andExpect(jsonPath("$[0].entranceSensorId").value(1))
                .andExpect(jsonPath("$[0].measuredValue").value(1));
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
    void spotMeasurementUpdatesSpotState() throws Exception {
        postJson("/parking-spot-measurements", "{\"parkingSpotSensorId\": 16, \"occupied\": true}")
                .andExpect(status().isCreated());
        mvc.perform(get("/parking-lots/1"))
                .andExpect(jsonPath("$.rows[3].spots[3].occupied").value(true))
                .andExpect(jsonPath("$.freeSpots").value(11));

        postJson("/parking-spot-measurements", "{\"parkingSpotSensorId\": 16, \"occupied\": false}")
                .andExpect(status().isCreated());
        mvc.perform(get("/parking-lots/1"))
                .andExpect(jsonPath("$.freeSpots").value(12));
    }

    @Test
    void entranceMeasurementChangesCarsInside() throws Exception {
        postJson("/entrance-measurements", "{\"entranceSensorId\": 1, \"measuredValue\": 1}")
                .andExpect(status().isCreated());
        mvc.perform(get("/parking-lots/1")).andExpect(jsonPath("$.carsInside").value(5));

        postJson("/entrance-measurements", "{\"entranceSensorId\": 1, \"measuredValue\": -1}")
                .andExpect(status().isCreated());
        mvc.perform(get("/parking-lots/1")).andExpect(jsonPath("$.carsInside").value(4));
    }

    @Test
    void invalidMeasurementsAre400() throws Exception {
        postJson("/entrance-measurements", "{\"entranceSensorId\": 1, \"measuredValue\": 2}")
                .andExpect(status().isBadRequest());
        postJson("/entrance-measurements", "{\"entranceSensorId\": 1}")
                .andExpect(status().isBadRequest());
        postJson("/parking-spot-measurements", "{\"parkingSpotSensorId\": 1}")
                .andExpect(status().isBadRequest());
    }

    @Test
    void unknownSensorsAre404() throws Exception {
        postJson("/entrance-measurements", "{\"entranceSensorId\": 99, \"measuredValue\": 1}")
                .andExpect(status().isNotFound());
        postJson("/parking-spot-measurements", "{\"parkingSpotSensorId\": 99, \"occupied\": true}")
                .andExpect(status().isNotFound());
    }
}
