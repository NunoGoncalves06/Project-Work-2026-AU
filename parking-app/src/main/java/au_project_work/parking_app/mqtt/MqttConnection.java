package au_project_work.parking_app.mqtt;

import org.eclipse.paho.client.mqttv3.MqttConnectOptions;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.integration.annotation.Router;
import org.springframework.integration.channel.DirectChannel;
import org.springframework.integration.config.EnableIntegration;
import org.springframework.integration.core.MessageProducer;
import org.springframework.integration.mqtt.core.DefaultMqttPahoClientFactory;
import org.springframework.integration.mqtt.core.MqttPahoClientFactory;
import org.springframework.integration.mqtt.inbound.MqttPahoMessageDrivenChannelAdapter;
import org.springframework.integration.mqtt.support.DefaultPahoMessageConverter;
import org.springframework.integration.mqtt.support.MqttHeaders;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.handler.annotation.Header;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.UUID;

@Configuration
@EnableIntegration
public class MqttConnection {

    // MQTT_USERNAME / MQTT_PASSWORD in the environment (docker-compose.prod.yml)
    @Value("${mqtt.username:}")
    private String mqttUsername;

    @Value("${mqtt.password:}")
    private String mqttPassword;

    // MQTT_CLIENT_ID (optional): the broker keys the queued messages by it; see clientId() for the default
    // (the sensor login could otherwise kick the backend off with the same id). Unset = a random id per process
    // (local dev), which means no queued messages survive a restart.
    @Value("${mqtt.client-id:}")
    private String mqttClientId;

    // Shared input channel
    @Bean
    public MessageChannel mqttInputChannel() {
        return new DirectChannel();
    }

    // channel the spotMeasurementListener listens to
    @Bean
    public MessageChannel spotMeasurementChannel() {
        return new DirectChannel();
    }

    // channel the entranceMeasurementListener listens to
    @Bean
    public MessageChannel entranceMeasurementChannel() {
        return new DirectChannel();
    }

    @Bean
    public MqttPahoClientFactory mqttClientFactory() {
        MqttConnectOptions options = new MqttConnectOptions();
        options.setServerURIs(new String[] { "tcp://mosquitto:1883" });
        // the broker restarting, or a blip on the lab wifi, otherwise leaves the adapter
        // disconnected for good and sensor messages stop arriving silently
        options.setAutomaticReconnect(true);
        // the broker keeps this client's subscriptions and queues QoS 1 messages while the
        // backend is down (restart, rebuild), and delivers them when it reconnects
        options.setCleanSession(false);
        // the production broker refuses anonymous clients; locally both stay empty and the
        // dev broker (mqtt/config/mosquitto.conf) lets us in without a login
        if (!mqttUsername.isEmpty()) {
            options.setUserName(mqttUsername);
            options.setPassword(mqttPassword.toCharArray());
        }
        DefaultMqttPahoClientFactory factory = new DefaultMqttPahoClientFactory();
        factory.setConnectionOptions(options);
        return factory;
    }

    // maps incoming messages to the listener channels
    @Router(inputChannel = "mqttInputChannel")
    public String route(@Header(MqttHeaders.RECEIVED_TOPIC) String topic) {
        if (topic.startsWith("carcounter/spots"))
            return "spotMeasurementChannel";
        if (topic.startsWith("carcounter/entrance"))
            return "entranceMeasurementChannel";
        return "nullChannel";
    }

    @Bean
    public MessageProducer inbound() {
        // adapter writes to the shared channel
        // the broker finds the queued messages by this id: keep it fixed (MQTT_CLIENT_ID), and unique per broker
        // (two backends with the same id on one broker would keep kicking each other off)
        MqttPahoMessageDrivenChannelAdapter adapter = new MqttPahoMessageDrivenChannelAdapter(
                clientId(),
                mqttClientFactory(),
                "carcounter/#"); // other topics are dropped
        adapter.setCompletionTimeout(5000);
        adapter.setConverter(new DefaultPahoMessageConverter());
        adapter.setQos(1);
        adapter.setOutputChannel(mqttInputChannel());
        return adapter;
    }

    /**
     * MQTT_CLIENT_ID if set. Otherwise, with a broker login (the server), an id derived from the password: the same on
     * every start, so the broker keeps the readings queued while the backend restarts, but not guessable from this
     * repository (anyone using the backend's id kicks it off the broker). Without a login (local dev): random.
     */
    String clientId() {
        if (!mqttClientId.isBlank()) {
            return mqttClientId.trim();
        }
        if (mqttPassword.isBlank()) {
            return "parking-backend-" + UUID.randomUUID().toString().substring(0, 8);
        }
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(("mqtt-client-id:" + mqttPassword).getBytes(StandardCharsets.UTF_8));
            return "parking-backend-" + HexFormat.of().formatHex(hash, 0, 6);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e); // SHA-256 is in every JVM
        }
    }
}
