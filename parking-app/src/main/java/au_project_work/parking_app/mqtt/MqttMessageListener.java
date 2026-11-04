package au_project_work.parking_app.mqtt;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.integration.annotation.ServiceActivator;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageHandler;
import org.springframework.messaging.MessagingException;

import au_project_work.parking_app.service.ParkingSpotMeasurementService;
import tools.jackson.databind.ObjectMapper; //to extract message payload
import tools.jackson.databind.JsonNode; //to extract message payload

@Configuration
public class MqttMessageListener {

    private static final Logger log = LoggerFactory.getLogger(MqttMessageListener.class);

    private final ParkingSpotMeasurementService parkingSpotMeasurementService;
    private final ObjectMapper objectMapper = new ObjectMapper(); // to extract data from payload

    public MqttMessageListener(ParkingSpotMeasurementService service) {
        this.parkingSpotMeasurementService = service;
    }

    @Bean
    @ServiceActivator(inputChannel = "mqttInputChannel")
    public MessageHandler handler() {

        return new MessageHandler() {

            @Override
            public void handleMessage(Message<?> message) throws MessagingException {
                String payload = message.getPayload().toString();
                // Nothing may be thrown back at the Paho client: an exception here propagates
                // out of its callback thread, drops the connection, and no further sensor
                // message arrives until the app restarts. One bad payload is skipped instead.
                try {
                    handle(payload);
                } catch (Exception e) {
                    log.warn("Skipping unusable spot sensor message: {}", payload, e);
                }
            }

            private void handle(String payload) {
                JsonNode json = objectMapper.readTree(payload);
                JsonNode deviceId = json.get("device_id");
                JsonNode occupied = json.get("occupied");
                if (deviceId == null || !deviceId.isIntegralNumber() || occupied == null || !occupied.isBoolean()) {
                    // asInt()/asBoolean() would quietly turn a missing or misspelled field into
                    // 0 / false, which reads as "sensor 0 reports free" rather than as a fault
                    throw new IllegalArgumentException(
                            "expected an integer device_id and a boolean occupied");
                }
                // same path as POST /parking-spot-measurements: updates the spot's current
                // state as well as the history, so the app sees the change
                parkingSpotMeasurementService.record(deviceId.asInt(), occupied.asBoolean());
            }
        };
    }
}
