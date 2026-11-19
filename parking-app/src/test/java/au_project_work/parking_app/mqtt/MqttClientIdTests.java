package au_project_work.parking_app.mqtt;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

class MqttClientIdTests {

    private static String idFor(String clientId, String password) {
        MqttConnection c = new MqttConnection();
        ReflectionTestUtils.setField(c, "mqttClientId", clientId);
        ReflectionTestUtils.setField(c, "mqttPassword", password);
        return c.clientId();
    }

    @Test
    void anExplicitIdWins() {
        assertThat(idFor(" my-id ", "secret")).isEqualTo("my-id");
    }

    @Test
    void withALoginTheIdIsStableAcrossStartsButNotThePublicOne() {
        String id = idFor("", "secret");
        assertThat(id).isEqualTo(idFor("", "secret")).startsWith("parking-backend-").isNotEqualTo("parking-backend");
        assertThat(id).isNotEqualTo(idFor("", "other"));
    }

    @Test
    void withoutALoginTheIdIsRandom() {
        assertThat(idFor("", "")).isNotEqualTo(idFor("", ""));
    }
}
