package au_project_work.parking_app.mqtt;

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
                System.out.println(message.getPayload());

                // save reading to database
                JsonNode json = objectMapper.readTree(message.getPayload().toString());
                parkingSpotMeasurementService.SaveParkingSpotMeasurement(
                        json.path("device_id").asInt(),
                        json.path("occupied").asBoolean());
            }

        };
    }
}
