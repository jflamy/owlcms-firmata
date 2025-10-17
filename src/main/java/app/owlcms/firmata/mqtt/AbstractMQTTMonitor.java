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

import app.owlcms.firmata.data.MQTTConfig;  // Update import to use data package version
import app.owlcms.firmata.ui.Main;
import app.owlcms.firmata.utils.WebSocketProtocol;
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
	// prevent multiple concurrent starts
	private volatile boolean startedFlag = false;
	// Add tracking of subscriptions to avoid duplicates
	private final Set<String> currentSubscriptions = new HashSet<>();

	public void close() {
		if (client == null) {
			setClosed(true);
			return;
		}
		try {
			setClosed(true);
			// Clear subscription tracking on disconnect
			currentSubscriptions.clear();
			client.disconnect();
			client.close();
			startedFlag = false;
		} catch (MqttException e) {
			logger.error("cannot close client {}", e.getMessage());
		}
	}

	public boolean connectionLoop(MqttAsyncClient mqttAsyncClient) throws MqttSecurityException {
		//logger.debug("connection loop {}", LoggerUtils.stackTrace());
		int attempt = 0;
		setClosed(false);
		long backoffMs = 500L; // start with 500ms
		final long MAX_BACKOFF_MS = 30_000L; // cap at 30s
		while (!mqttAsyncClient.isConnected() && !isClosed()) {
			try {
				// Clear subscriptions on connection loss to allow re-subscription
				if (attempt > 0) {
					currentSubscriptions.clear();
				}
				// doConnect will generate a new client Id, and wait for completion
				doConnect();
				// reset backoff on success
				backoffMs = 500L;
			} catch (MqttSecurityException e) {
				// Security exceptions (bad credentials or "Already connected" = auth failure)
				// should not be retried - log and immediately rethrow
				logger.error("MQTT security exception - aborting connection: {}", e.getMessage(), e);
				throw e;
			} catch (Exception e) {
				if (attempt == 0) {
					logger.error("{}", e.getMessage(),
							e.getCause() != null ? e.getCause().getMessage() : e);
				} else {
					logger.debug("connection attempt {} failed: {}", attempt, e.getMessage());
				}
				// sleep with backoff before retrying
				sleep((int) backoffMs);
				backoffMs = Math.min(MAX_BACKOFF_MS, backoffMs * 2);
			}
			attempt++;
		}
		return mqttAsyncClient.isConnected();
	}

	public MqttAsyncClient createMQTTClient(String fopName) throws MqttException {
		String server = MQTTConfig.getCurrent().getMqttServer();
		server = (server != null ? server : "127.0.0.1");
		String port = MQTTConfig.getCurrent().getMqttPort();
		port = (port != null ? port : "1883");
		// Decide broker URI based on port rules (ws/wss for websocket ports, mqtt -> tcp)
		String proto = WebSocketProtocol.selectProtocol(port);
		String brokerUri;
		if ("ws".equals(proto) || "wss".equals(proto)) {
			brokerUri = WebSocketProtocol.buildUrl(server, port);
		} else {
			// plain MQTT over TCP (Paho expects tcp:// or ssl:// for non-websocket transports)
			brokerUri = "tcp://" + server + ":" + port;
		}
		Main.getStartupLogger().info("connecting to MQTT {}", brokerUri);

	// Build a parseable client id that includes the device identifier so it is one-per-device.
	String devicePart = sanitizeClientIdPart(getDeviceIdentifier());
	String genClientId = fopName + "_" + devicePart;
		client = new MqttAsyncClient(brokerUri,
		        genClientId, // ClientId
		        new MemoryPersistence()); // Persistence

		// Log where the client was created from (caller hint)
		StackTraceElement[] st = Thread.currentThread().getStackTrace();
		String caller = st.length > 3 ? st[3].toString() : "(unknown)";
		logger.info("Created MQTT client {} for fop='{}' (caller={})", genClientId, fopName, caller);
		return client;
	}

	/**
	 * Turn a device identifier into a small parseable token safe for MQTT client IDs.
	 * Replaces non-alphanumeric characters with '_' and lower-cases the result.
	 * Truncates to a reasonable length to avoid extremely long client ids.
	 */
	private String sanitizeClientIdPart(String raw) {
		if (raw == null) {
			return "unknown_device";
		}
		// Replace any character that is not a letter, number, dash or underscore
		String cleaned = raw.replaceAll("[^A-Za-z0-9_-]+", "_").toLowerCase();
		// Trim leading/trailing underscores
		cleaned = cleaned.replaceAll("^_+|_+$", "");
		if (cleaned.isEmpty()) {
			return "device";
		}
		// Limit length to 40 characters to keep client id compact
		if (cleaned.length() > 40) {
			cleaned = cleaned.substring(0, 40);
		}
		return cleaned;
	}
	
	public void doConnect() throws MqttSecurityException, MqttException {
		userName = MQTTConfig.getCurrent().getMqttUsername();
		password = MQTTConfig.getCurrent().getMqttPassword();
		MqttConnectOptions connOpts = setupMQTTClient(userName, password);
		try {
			String cid = client != null ? client.getClientId() : "(null)";
			String uri = client != null ? client.getServerURI() : "(null)";
			logger.info("Attempting MQTT connect: clientId={} serverURI={}", cid, uri);
			client.connect(connOpts).waitForCompletion();
			logger.info("MQTT connected: clientId={} serverURI={}", cid, uri);
		} catch (MqttSecurityException mse) {
			// Real security exception from Paho (bad credentials)
			logger.error("MQTT security exception (bad credentials): {}", mse.getMessage());
			throw mse;
		} catch (MqttException me) {
			// "Already connected" typically indicates authentication failure with WebSocket
			// Check both the exception message and cause message
			String fullMessage = me.toString();
			Throwable cause = me.getCause();
			if (cause != null) {
				fullMessage += " | " + cause.toString();
			}
			
			if (fullMessage.contains("Already connected")) {
				logger.error("Connection already in progress or failed auth - treating as security error: {}", fullMessage);
				throw new MqttSecurityException(4); // Code 4 = connection lost (repurposed for auth failure)
			}
			logger.error("MQTT connect failed for client {}: {}", client != null ? client.getClientId() : "(null)", me.toString());
			logger.debug("MQTT connect exception", me);
			throw me;
		}
		
		// Only subscribe if we haven't already subscribed to this topic
		String subscription = getSubscription();
		if (!currentSubscriptions.contains(subscription)) {
			client.subscribe(subscription, 0);
			currentSubscriptions.add(subscription);
			logger.info("Monitor {} [{}] subscribed to {} {}", 
				getName(), getDeviceIdentifier(), subscription, client.getCurrentServerURI());
		} else {
			logger.debug("Monitor {} [{}] already subscribed to {}", 
				getName(), getDeviceIdentifier(), subscription);
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
				if (client != null && !currentSubscriptions.contains(topic)) {
					client.subscribe(topic, 0);
					currentSubscriptions.add(topic);
					// Use getDeviceInfo() for logging
					logger.info("Monitor {} [{}] subscribed to {} {}", clientId, getDeviceInfo(), topic, brokerUri);
				} else if (currentSubscriptions.contains(topic)) {
					logger.debug("Monitor {} [{}] already subscribed to {}", clientId, getDeviceInfo(), topic);
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

	public synchronized void start(String fopName) throws MqttSecurityException {
		if (startedFlag) {
			logger.debug("start() called but monitor already started for fop='{}'", fopName);
			return;
		}
		String mqttServer = MQTTConfig.getCurrent().getMqttServer();
		if (mqttServer == null || mqttServer.isBlank()) {
			logger.info("no MQTT server configured, skipping");
			return;
		}

		// Mark as started to prevent concurrent start attempts creating multiple clients
		startedFlag = true;
		try {
			logger.info("Starting MQTT monitor for fop='{}'", fopName);
			client = createMQTTClient(fopName);
			connectionLoop(client);
		} catch (MqttSecurityException e) {
			// Rethrow security exceptions immediately - don't retry
			logger.error("MQTT security exception for fop='{}': {}", fopName, e.getMessage());
			startedFlag = false;
			throw e;
		} catch (MqttException e) {
			logger.error("cannot initialize MQTT: {}", e);
			// allow retries in future start attempts
			startedFlag = false;
		} catch (Throwable t) {
			logger.error("unexpected error while starting MQTT monitor: {}", t.toString());
			startedFlag = false;
		}
	}
	
	public void stop() {
		try {
			// Clear subscription tracking on stop
			currentSubscriptions.clear();
			client.disconnect();
			client.close();
			startedFlag = false;
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
		// Use sane MQTT connection defaults
		connOpts.setKeepAliveInterval(60); // seconds
		connOpts.setConnectionTimeout(30); // seconds for TCP connect
		connOpts.setAutomaticReconnect(true);
		logger.debug("MQTT connect options: keepAlive={}s, timeout={}s, autoReconnect={}",
				connOpts.getKeepAliveInterval(), connOpts.getConnectionTimeout(), true);
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
