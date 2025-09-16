package mqtt;

import java.nio.charset.StandardCharsets;

import org.eclipse.paho.client.mqttv3.IMqttDeliveryToken;
import org.eclipse.paho.client.mqttv3.MqttCallback;
import org.eclipse.paho.client.mqttv3.MqttClient;
import org.eclipse.paho.client.mqttv3.MqttConnectOptions;
import org.eclipse.paho.client.mqttv3.MqttException;
import org.eclipse.paho.client.mqttv3.MqttMessage;
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence;
import org.junit.Test;
import org.slf4j.LoggerFactory;

import ch.qos.logback.classic.Logger;
import app.owlcms.firmata.utils.WebSocketProtocol;

public class MQTTRoundTrip {
	private static final String OWLCMS_FOP = "owlcms/test/#";
	static Logger logger = (Logger) LoggerFactory.getLogger(MQTTRoundTrip.class);

	public class MQTTTestCallback implements MqttCallback {

		@Override
		public void connectionLost(Throwable cause) {
		}

		@Override
		public void messageArrived(String topic, MqttMessage message) throws Exception {
			String messageStr = new String(message.getPayload(), StandardCharsets.UTF_8);
			long before = Long.parseLong(messageStr);
			logger.info("round trip timing = {}ms", System.currentTimeMillis() - before);
		}

		@Override
		public void deliveryComplete(IMqttDeliveryToken token) {
		}

	}

	private MQTTTestCallback callback = new MQTTTestCallback();

	public static MqttClient createMQTTClient(String fopName) throws MqttException {
		String server = "127.0.0.1";
		String port =  "1883";
		String proto = WebSocketProtocol.selectProtocol(port);
		String brokerUri;
		if ("ws".equals(proto) || "wss".equals(proto)) {
			brokerUri = WebSocketProtocol.buildUrl(server, port);
		} else {
			brokerUri = "tcp://" + server + ":" + port;
		}
		logger.info("connecting to MQTT {}", brokerUri);

		MqttClient client = new MqttClient(brokerUri,
				fopName + "_" + MqttClient.generateClientId(), // ClientId
				new MemoryPersistence()); // Persistence
		return client;
	}

	private MqttConnectOptions setUpConnectionOptions(String username, String password) {
		MqttConnectOptions connOpts = new MqttConnectOptions();
		connOpts.setCleanSession(true);
		if (username != null) {
			connOpts.setUserName(username);
		}
		if (password != null) {
			connOpts.setPassword(password.toCharArray());
		}
		connOpts.setCleanSession(true);
		// connOpts.setAutomaticReconnect(true);
		return connOpts;
	}

	@Test
	public void testRoundTrip() throws InterruptedException, MqttException {
		Thread t1 = new Thread(() -> {
			try {
				MqttClient client = createMQTTClient("A");
				MqttConnectOptions connOpts = setUpConnectionOptions("test", "test");
				client.connect(connOpts);
				client.setTimeToWait(-1);
				client.setCallback(callback);
				client.subscribe(OWLCMS_FOP, 0);
				for (int i = 0; i < 20; i++) {
					long before = System.currentTimeMillis();
					client.publish("owlcms/test/A",
							new MqttMessage(Long.toString(before).getBytes(StandardCharsets.UTF_8)));
				}
				client.disconnect();
				client.close(); // Properly close the client to prevent resource leaks
			} catch (MqttException e) {
				logger.error("MQTT error: {}", e.getMessage());
			}
		});
		t1.start();
		t1.join(20000);
	}

}
