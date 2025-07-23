package app.owlcms.firmata.mqtt;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

import org.eclipse.paho.client.mqttv3.IMqttDeliveryToken;
import org.eclipse.paho.client.mqttv3.MqttCallback;
import org.eclipse.paho.client.mqttv3.MqttMessage;
import org.slf4j.LoggerFactory;

import app.owlcms.firmata.eventhandlers.OutputEventHandler;
import app.owlcms.firmata.refdevice.RefDevice;
import ch.qos.logback.classic.Logger;

/**
 * This class contains the routines executed when an MQTT message is received.
 */
public class FopMQTTCallback implements MqttCallback {
    // Add deduplication tracking
    private final Map<String, Long> lastProcessedMessages = new HashMap<>();
    private final long MESSAGE_DEDUP_WINDOW_MS = 100;  // Deduplicate messages within 100ms

    final Logger logger = (Logger) LoggerFactory.getLogger(MqttCallback.class);
    private final FopMQTTMonitor mqttMonitor;
    private OutputEventHandler outputEventHandler;
    private RefDevice board;

    FopMQTTCallback(FopMQTTMonitor mqttMonitor, OutputEventHandler outputEventHandler, RefDevice board) {
        this.outputEventHandler = outputEventHandler;
        this.mqttMonitor = mqttMonitor;
        this.board = board;
    }

    @Override
    public void connectionLost(Throwable cause) {
        logger.debug("{}lost connection to MQTT: {}", this.mqttMonitor.getName(), cause.getLocalizedMessage());
        // Reset deduplication state on connection loss
        lastProcessedMessages.clear();
        // Called when the client lost the connection to the broker
        this.mqttMonitor.connectionLoop(this.mqttMonitor.client);
    }

    @Override
    public void deliveryComplete(IMqttDeliveryToken token) {
        // required by abstract class
    }

    @Override
    public void messageArrived(String topic, MqttMessage message) throws Exception {
        String messageStr = new String(message.getPayload(), StandardCharsets.UTF_8);
        var ntopic = topic.trim();

        // Simple message deduplication to avoid duplicate processing
        String messageKey = ntopic + ":" + messageStr;
        long now = System.currentTimeMillis();
        Long lastTime = lastProcessedMessages.get(messageKey);

        if (lastTime != null && (now - lastTime) < MESSAGE_DEDUP_WINDOW_MS) {
            // This is a duplicate message within our time window - skip it
            logger.debug("Skipping duplicate message: {}", ntopic);
            return;
        }

        // Update the last processed time
        lastProcessedMessages.put(messageKey, now);

        // Clean up old entries to prevent memory leak
        if (lastProcessedMessages.size() > 100) {
            lastProcessedMessages.entrySet().removeIf(
                entry -> (now - entry.getValue()) > 5000); // Remove entries older than 5 seconds
        }

        // Continue with regular processing
        if (ntopic.startsWith("owlcms/fop/") && ntopic.endsWith("/" + this.mqttMonitor.getName())) {
            logger.debug("handling {} {}", ntopic, messageStr);
            outputEventHandler.handle(simplifyTopic(ntopic), messageStr, board);
        } else if (ntopic.endsWith("/config")) {
            // ignore, is handled by ConfigMQTTCallback
        } else {
            logger.error("{} Malformed MQTT unrecognized topic message topic='{}' message='{}'",
                    this.mqttMonitor.getName(), topic, messageStr);
        }
    }

    /**
     * Remove leading and trailing parts to simplify matching
     * 
     * @param topic
     * @return
     */
    private String simplifyTopic(String topic) {
        String simpleTopic = topic.substring(topic.indexOf("/") + 1);
        simpleTopic = simpleTopic.substring(0, simpleTopic.lastIndexOf('/'));
        return simpleTopic;
    }

    // /**
    // * Tell others that the refbox has given the down signal
    // *
    // * @param topic
    // * @param messageStr
    // */
    // private void postFopEventDownEmitted(String topic, String messageStr) {
    // messageStr = messageStr.trim();
    // }

}