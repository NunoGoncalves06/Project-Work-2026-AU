package au_project_work.parking_app.mqtt;

import org.eclipse.paho.client.mqttv3.MqttConnectOptions;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.integration.annotation.ServiceActivator;
import org.springframework.integration.channel.DirectChannel;
import org.springframework.integration.config.EnableIntegration;
import org.springframework.integration.core.MessageProducer;
import org.springframework.integration.mqtt.core.DefaultMqttPahoClientFactory;
import org.springframework.integration.mqtt.core.MqttPahoClientFactory;
import org.springframework.integration.mqtt.inbound.MqttPahoMessageDrivenChannelAdapter;
import org.springframework.integration.mqtt.support.DefaultPahoMessageConverter;
import org.springframework.integration.mqtt.support.MqttHeaders;
import org.springframework.integration.router.HeaderValueRouter;
import org.springframework.messaging.MessageChannel;

@Configuration
@EnableIntegration
public class MqttConnection {

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

    // to do: add default channel

    @Bean
    public MqttPahoClientFactory mqttClientFactory() {
        MqttConnectOptions options = new MqttConnectOptions();
        options.setServerURIs(new String[] { "tcp://mosquitto:1883" });
        // the broker restarting, or a blip on the lab wifi, otherwise leaves the
        // adapter
        // disconnected for good and sensor messages stop arriving silently
        options.setAutomaticReconnect(true);
        options.setCleanSession(true);
        DefaultMqttPahoClientFactory factory = new DefaultMqttPahoClientFactory();
        factory.setConnectionOptions(options);
        return factory;
    }

    // maps incoming messages to the listener channels
    @ServiceActivator(inputChannel = "mqttInputChannel")
    @Bean
    public HeaderValueRouter router() {
        HeaderValueRouter router = new HeaderValueRouter(MqttHeaders.RECEIVED_TOPIC);
        router.setChannelMapping("carcounter/spots", "spotMeasurementChannel");
        router.setChannelMapping("carcounter/entrance", "entranceMeasurementChannel");
        return router;
    }

    @Bean
    public MessageProducer inbound() {
        // adapter writes to the shared channel
        MqttPahoMessageDrivenChannelAdapter adapter = new MqttPahoMessageDrivenChannelAdapter("testClient",
                mqttClientFactory(),
                "carcounter/spots", "carcounter/entrance"); // handle other messages?
        adapter.setCompletionTimeout(5000);
        adapter.setConverter(new DefaultPahoMessageConverter());
        adapter.setQos(1);
        adapter.setOutputChannel(mqttInputChannel());
        return adapter;
    }
}
