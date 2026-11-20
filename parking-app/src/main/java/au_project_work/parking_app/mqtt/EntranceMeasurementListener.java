package au_project_work.parking_app.mqtt;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.integration.annotation.ServiceActivator;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageHandler;
import org.springframework.messaging.MessagingException;

import au_project_work.parking_app.service.EntranceMeasurementService;
import tools.jackson.databind.ObjectMapper; //to extract message payload
import tools.jackson.databind.JsonNode; //to extract message payload

@Configuration
public class EntranceMeasurementListener {
    private static final Logger log = LoggerFactory.getLogger(EntranceMeasurementListener.class);

    private final EntranceMeasurementService entranceMeasurementService;
    private final ObjectMapper objectMapper = new ObjectMapper(); // to extract data from payload

    public EntranceMeasurementListener(EntranceMeasurementService service) {
        this.entranceMeasurementService = service;
    }

    @Bean
    @ServiceActivator(inputChannel = "entranceMeasurementChannel")
    public MessageHandler entranceMessageHandler() {

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
                    log.warn("Skipping unusable entrance sensor message: {}", payload, e);
                }
            }

            private void handle(String payload) {
                JsonNode json = objectMapper.readTree(payload);
                JsonNode entranceSensorId = json.get("device_id");
                JsonNode measuredValue = json.get("dir");
                if (entranceSensorId == null || !entranceSensorId.isIntegralNumber() || measuredValue == null
                        || !measuredValue.isIntegralNumber()) {
                    throw new IllegalArgumentException(
                            "expected an integer device_id and an integer dir");
                }
                DbRetry.run(() -> entranceMeasurementService.record(entranceSensorId.asInt(), measuredValue.asInt()));
            }
        };
    }

}
