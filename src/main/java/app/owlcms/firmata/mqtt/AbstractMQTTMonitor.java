package app.owlcms.firmata.mqtt;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Set;

import org.eclipse.paho.client.mqttv3.MqttAsyncClient;
import org.eclipse.paho.client.mqttv3.MqttConnectOptions;
import org.eclipse.paho.client.mqttv3.MqttException;
import org.eclipse.paho.client.mqttv3.MqttMessage;
import org.eclipse.paho.client.mqttv3.MqttPersistenceException;
import org.eclipse.paho.client.mqttv3.MqttSecurityException;
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence;
import org.slf4j.LoggerFactory;

import app.owlcms.firmata.data.MQTTConfig;
import app.owlcms.firmata.ui.Main;
import ch.qos.logback.classic.Logger;

public abstract class AbstractMQTTMonitor {

	protected MqttAsyncClient client;
	protected String password;
	protected String userName;
	private boolean closed;
	private Logger logger = (Logger) LoggerFactory.getLogger(AbstractMQTTMonitor.class);
	private String name;
	private String subscription;
	// Store the clientId and brokerUri for logging since we need it in multiple places
	protected String clientId;
	protected String brokerUri;
	// Add tracking of subscriptions to avoid duplicates
	private final Set<String> currentSubscriptions = new HashSet<>();

	public void close() {
		if (client == null) {
			setClosed(true);
			return;
		}
		try {
			setClosed(true);
			client.disconnect();
			client.close();
		} catch (MqttException e) {
			logger.error("cannot close client {}", e.getMessage());
		}
	}

	public boolean connectionLoop(MqttAsyncClient mqttAsyncClient) {
		//logger.debug("connection loop {}", LoggerUtils.stackTrace());
		int i = 0;
		setClosed(false);
		while (!mqttAsyncClient.isConnected() && !isClosed()) {
			try {
				// doConnect will generate a new client Id, and wait for completion
				doConnect();
			} catch (Exception e) {
				if (i == 0) {
					logger.error("{}", e.getMessage(),
					        e.getCause() != null ? e.getCause().getMessage() : e);
				}
			}
			sleep(1000);
			i++;
		}
		return false;
	}

	public MqttAsyncClient createMQTTClient(String fopName) throws MqttException {
		String server = MQTTConfig.getCurrent().getMqttServer();
		server = (server != null ? server : "127.0.0.1");
		String port = MQTTConfig.getCurrent().getMqttPort();
		port = (port != null ? port : "1883");
		String protocol = port.startsWith("8") ? "ssl://" : "tcp://";
		Main.getStartupLogger().info("connecting to MQTT {}{}:{}", protocol, server, port);

		client = new MqttAsyncClient(protocol + server + ":" + port,
		        fopName + "_f_" + System.currentTimeMillis(), // ClientId
		        new MemoryPersistence()); // Persistence
		return client;
	}
	
	public void doConnect() throws MqttSecurityException, MqttException {
		userName = MQTTConfig.getCurrent().getMqttUsername();
		password = MQTTConfig.getCurrent().getMqttPassword();
		MqttConnectOptions connOpts = setupMQTTClient(userName, password);
		client.connect(connOpts).waitForCompletion();
		
		// Only subscribe if not already subscribed
		if (currentSubscriptions.add(getSubscription())) {
			client.subscribe(getSubscription(), 0);
			logger.info("Monitor {} [{}] subscribed to {} {}", 
				getName(), getDeviceIdentifier(), getSubscription(), client.getCurrentServerURI());
		} else {
			logger.debug("Already subscribed to {} {}", getSubscription(), client.getCurrentServerURI());
		}
	}

	/**
	 * Get device identifier for logging purposes.
	 * @return String identifying the device being monitored
	 */
	protected String getDeviceIdentifier() {
	    return "ConfigMonitor";
	}

	protected void doConnect(String... topics) throws MqttException, MqttSecurityException {
		try {
			userName = MQTTConfig.getCurrent().getMqttUsername();
			password = MQTTConfig.getCurrent().getMqttPassword();
			MqttConnectOptions connOpts = setupMQTTClient(userName, password);
			
			// Store client ID and broker URI for logging purposes
			clientId = client.getClientId();
			brokerUri = client.getServerURI();
			
			client.connect(connOpts).waitForCompletion();
			
			for (String topic : topics) {
				if (client != null) {
					client.subscribe(topic, 0);
					// Use getDeviceInfo() for logging
					logger.info("Monitor {} [{}] subscribed to {} {}", clientId, getDeviceInfo(), topic, brokerUri);
				}
			}
		} catch (MqttException e) {
			logger.error("failed MQTT connect {}", e.getMessage());
			throw e;
		}
	}

	/**
	 * Gets device information for logging purposes.
	 * Subclasses should override this to provide specific device information.
	 * @return String representing the device being monitored
	 */
	protected String getDeviceInfo() {
	    // Default implementation
	    return "Server Config";
	}

	public String getName() {
		return name;
	}

	public String getSubscription() {
		return subscription;
	}
	
	public boolean isConnected() {
		return client!= null && client.isConnected();
	}


	public void publishMqttMessage(String topic, String message) throws MqttException, MqttPersistenceException {
		MqttMessage message2 = new MqttMessage(message.getBytes(StandardCharsets.UTF_8));
		client.publish(topic, message2);
	}

	public void quickCheckConnection() throws NumberFormatException, IOException {
		try (Socket socket = new Socket()) {
			int portNum = 0;
			try {
				portNum = Integer.parseInt(MQTTConfig.getCurrent().getMqttPort());
			} catch (NumberFormatException e) {
				throw new NumberFormatException("Port Number must be a number: "+ MQTTConfig.getCurrent().getMqttPort());
			}
			try {
				socket.connect(new InetSocketAddress(
						MQTTConfig.getCurrent().getMqttServer(),
				        portNum), 
						2000);
			} catch (IOException e) {
				logger.error("{}",e.getLocalizedMessage());
				throw e;
			}
		}
	}

	public synchronized void setClosed(boolean b) {
		this.closed = b;
	}

	public void setName(String name) {
		this.name = name;
	}

	public void setSubscription(String subscription) {
		this.subscription = subscription;
	}

	public void start(String fopName) {
		try {
			String mqttServer = MQTTConfig.getCurrent().getMqttServer();
			if (mqttServer != null && !mqttServer.isBlank()) {
				client = createMQTTClient(fopName);
				connectionLoop(client);
			} else {
				logger.info("no MQTT server configured, skipping");
			}
		} catch (MqttException e) {
			logger.error("cannot initialize MQTT: {}", e);
		}
	}
	
	public void stop() {
		try {
			client.disconnect();
			client.close();
		} catch (MqttException e) {
			logger.error("cannot close: {}", e);
		}
	}

	protected MqttConnectOptions setUpConnectionOptions(String username, String password) {
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

	protected abstract MqttConnectOptions setupMQTTClient(String userName2, String password2);

	private synchronized boolean isClosed() {
		return this.closed;
	}

	private void sleep(int ms) {
		try {
			Thread.sleep(ms);
		} catch (InterruptedException e) {
		}
	}

}
