package app.owlcms.firmata.data;

import java.io.IOException;
import java.io.OutputStream; // Added import
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentSkipListMap;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import org.firmata4j.firmata.FirmataDevice;
import org.firmata4j.transport.JSerialCommTransport;
import org.slf4j.LoggerFactory;

import com.fazecast.jSerialComm.SerialPort;
import com.google.common.eventbus.AsyncEventBus;
import com.google.common.eventbus.EventBus;

import app.owlcms.firmata.mqtt.ConfigMQTTMonitor;
import app.owlcms.firmata.ui.FirmataService;
import app.owlcms.firmata.utils.LoggerUtils;
import app.owlcms.firmata.utils.Sleeper;
import app.owlcms.utils.ResourceWalker;

public class MQTTConfig {
	
	private static Logger logger = (Logger) LoggerFactory.getLogger(MQTTConfig.class);
	private static MQTTConfig current = null;
	private static final String SETTINGS_PROPERTIES = "settings.properties"; // Define for consistency

	
	private String fop;
	private List<String> fops;
	private String mqttPassword;
	private String mqttPort;
	private String mqttServer;
	private String mqttUsername;
	private ConfigMQTTMonitor configMqttMonitor;
	private TreeMap<String, DeviceConfig> portToConfig;
	private Map<String, String> portToFirmware;
	private AsyncEventBus uiEventBus;
	private List<FirmataService> services;

	// Add a field to store platforms for later use
	private List<String> availablePlatforms = new ArrayList<>();

	public static MQTTConfig getCurrent() {
		if (current == null) {
			current = new MQTTConfig();
		}
		return current;
	}
	
	private MQTTConfig() {
		this.mqttServer = "192.168.\u2014.\u2014";
		this.mqttPort = "1883";
		this.mqttUsername = "";
		this.mqttPassword = "";
		this.fops = new ArrayList<>();
		this.portToConfig = new TreeMap<>();
		this.services = new ArrayList<>();
		this.portToFirmware = new ConcurrentSkipListMap<>(); // Initialize to avoid NPE
	}

	public String getFop() {
		return this.fop;
	}

	public List<String> getFops() {
		return this.fops;
	}


	public String getMqttPassword() {
		if (mqttPassword != null) {
			return mqttPassword;
		}
		return "";
	}

	public String getMqttPort() {
		if (mqttPort != null) {
			return mqttPort;
		}
		return "";
	}

	public String getMqttServer() {
		if (mqttServer != null) {
			return mqttServer;
		}
		return "";
	}

	public String getMqttUsername() {
		if (mqttUsername != null) {
			return mqttUsername;
		}
		return "";
	}

	public void register(FirmataService mm) {
		services.add(mm);
	}

	public void setFop(String platform) {
		this.fop = platform;
		logger.info("Platform (fop) set to: {}. Saving settings.", this.fop);
		saveSettings(); // Automatically save settings when fop is changed
	}

	public void setFops(List<String> fops) {
		this.fops = fops;
	}

	public void setMqttPassword(String mqttPassword) {
		this.mqttPassword = mqttPassword;
	}

	public void setMqttPort(String mqttPort) {
		this.mqttPort = mqttPort;
	}


	public void setMqttServer(String value) {
		this.mqttServer = value;
	}


	public void setMqttUsername(String mqttUsername) {
		this.mqttUsername = mqttUsername;
	}


	public boolean isConnected() {
		return getConfigMqttMonitor().isConnected();
	}


	private ConfigMQTTMonitor getConfigMqttMonitor() {
		return configMqttMonitor;
	}
	
	public void closeAll() {
		for (FirmataService mm : services) {
			mm.stopDevice(()->{});
		}
		services.clear();
	}


	public void setConfigMqttMonitor(ConfigMQTTMonitor configMqttMonitor) {
		this.configMqttMonitor = configMqttMonitor;
	}


	public static boolean fullyConnected() {
		logger.debug("connected = {} fop = {}", getCurrent().isConnected(), getCurrent().getFop());
		return getCurrent().isConnected() && (getCurrent().getFop() != null);
	}


	public TreeMap<String, DeviceConfig> getPortToConfig() {
		return portToConfig;
	}


	public void setPortToConfig(TreeMap<String, DeviceConfig> portToConfig) {
		this.portToConfig = portToConfig;
	}
	
	public Map<String, String> getPortToFirmware() {
		if (portToFirmware == null) {
			portToFirmware = new ConcurrentSkipListMap<>();
		}
		return portToFirmware;
	}

	public void setPortToFirmware(Map<String, String> portToFirmware) {
		if (portToFirmware == null) {
			this.portToFirmware = new ConcurrentSkipListMap<>();
		} else {
			this.portToFirmware = portToFirmware;
		}
	}

	public boolean connectedNoPlatform() {
		return getCurrent().isConnected() && (getCurrent().getFop() == null);
	}
	
	public List<SerialPort> getSerialPorts() {
		SerialPort[] ports = SerialPort.getCommPorts();
		return Arrays.asList(ports);
	}
	
	public synchronized void saveSettings() {
		Properties p = new Properties();
		Path localDirPath = ResourceWalker.getLocalDirPath();
		if (!Files.exists(localDirPath)) {
			try {
				Files.createDirectories(localDirPath);
			} catch (IOException e) {
				logger.error("Cannot create directory {}: {}", localDirPath, e.getMessage());
				return;
			}
		}
		Path propertiesPath = localDirPath.resolve(SETTINGS_PROPERTIES); // Consistent use

		// Populate properties with current values, only including the specified keys
		if (this.fop != null && !this.fop.isEmpty()) {
			p.setProperty("fop", this.fop);
		}
		if (this.mqttPort != null && !this.mqttPort.isEmpty()) {
			p.setProperty("mqttPort", this.mqttPort);
		}
		if (this.mqttServer != null && !this.mqttServer.isEmpty()) {
			p.setProperty("mqttServer", this.mqttServer);
		}
		if (this.mqttUsername != null && !this.mqttUsername.isEmpty()) {
			p.setProperty("mqttUsername", this.mqttUsername);
		}
		// mqttPassword is intentionally not saved to the properties file for security.
		// mqttClientId and mqttTopic are also not in the specified list.

		try (OutputStream out = Files.newOutputStream(propertiesPath,
				StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE)) {
			p.store(out, "owlcms server connection information");
			logger.info("Saved settings to {}: fop={}, mqttPort={}, mqttServer={}, mqttUsername={}",
					propertiesPath,
					p.getProperty("fop", ""),
					p.getProperty("mqttPort", ""),
					p.getProperty("mqttServer", ""),
					p.getProperty("mqttUsername", ""));
		} catch (IOException e) {
			logger.error("Cannot write {}: {}", propertiesPath, e.getMessage());
		}
	}

	public synchronized void readSettings() {
		Properties p = new Properties();
		Path devicesDir = ResourceWalker.getLocalDirPath();
		Path settings = devicesDir.resolve(SETTINGS_PROPERTIES); // Consistent use
		try {
			p.load(Files.newInputStream(settings, StandardOpenOption.READ));
			
			// Load only MQTT connection details and platform selection
			String mqttServerProp = p.getProperty("mqttServer");
			String oldServer = mqttServer;  // store previous value
			mqttServer = mqttServerProp != null ? mqttServerProp : mqttServer;
			logger.error("MQTT Server address changed from '{}' to '{}'", oldServer, mqttServer);  // using ERROR to ensure visibility
			String mqttPortProp = p.getProperty("mqttPort");
			mqttPort = mqttPortProp != null ? mqttPortProp : mqttPort;
			String mqttUsernameProp = p.getProperty("mqttUsername");
			mqttUsername = mqttUsernameProp != null ? mqttUsernameProp : mqttUsername;
			// Load platform (fop) from settings
			// Do not call setFop here to avoid recursive save during read
			this.fop = p.getProperty("fop"); 
			logger.info("Read platform from settings: {}", fop);
		} catch (IOException e) {
			logger.warn("cannot read settings {}", e.getMessage());
		}
		
		// Device configs are intentionally not loaded from settings
		// They will be discovered dynamically at runtime
	}


	public EventBus getUiEventBus() {
		if (this.uiEventBus == null) {
			this.uiEventBus = new AsyncEventBus("owlcms-firmata", new ThreadPoolExecutor(8, Integer.MAX_VALUE,
			        60L, TimeUnit.SECONDS,
			        new SynchronousQueue<Runnable>()));
		}
		return uiEventBus;
	}
	
	public Map<String, String> buildPortToFirmwareMap(Consumer<Integer> progressUpdate) {
		portToFirmware = new ConcurrentSkipListMap<>();
		int i = 1;
		progressUpdate.accept(1);
		
		List<SerialPort> serialPorts = getSerialPorts();
		logger.info("Starting firmware detection on {} ports", serialPorts.size());
		
		for (SerialPort sp : serialPorts) {
			FirmataDevice device = null;
			try {
				String systemPortName = sp.getSystemPortName();
				logger.debug("Checking port: {}", systemPortName);
				
				// Set up device with proper transport
				device = new FirmataDevice(new JSerialCommTransport(systemPortName));
				
				// Start device with timeout
				long startTime = System.currentTimeMillis();
				device.start();
				logger.debug("Device start initiated on port {}", systemPortName);
				
				// Use ensureInitializationIsDone without timeout (already handled internally)
				try {
					device.ensureInitializationIsDone();
					logger.debug("Device initialization complete on port {}", systemPortName);
					
					String firmware = device.getFirmware();
					firmware = firmware.replace(".ino", ""); // Ensure .ino is stripped
					logger.info("Found firmware '{}' on port {} (took {}ms)", 
							firmware, systemPortName, System.currentTimeMillis() - startTime);
					portToFirmware.put(systemPortName, firmware);
				} catch (Exception e) { // Catch more general exceptions from ensureInitializationIsDone
					logger.debug("Device initialization failed on port {}: {}", 
						systemPortName, e.getMessage());
				}
			} catch (Exception e) { // Catch broader exceptions from device creation/start
				logger.debug("Could not detect firmware on port {}: {}", 
					sp.getSystemPortName(), e.getMessage());
			} finally {
				try {
					if (device != null) {
						device.stop();
					}
				} catch (IOException e1) {
					LoggerUtils.logError(logger, e1);
				}
				progressUpdate.accept(++i);
			}
		}
		
		logger.info("Completed firmware detection, found {} devices", portToFirmware.size());
		return portToFirmware;
	}

	// Fix the debugSaveSettings method
	public void debugSaveSettings() {
		logger.info("DEBUG: Saving config - current FOP value: {}", getFop());
		saveSettings();
		
		// Read back from file to verify
		Path devicesDir = ResourceWalker.getLocalDirPath();
		Path settings = devicesDir.resolve(SETTINGS_PROPERTIES); // Consistent use
		try {
			Properties debugProps = new Properties();
			debugProps.load(Files.newInputStream(settings, StandardOpenOption.READ));
			logger.info("DEBUG: After save - FOP value from Properties: {}", debugProps.getProperty("fop"));
		} catch (IOException e) {
			logger.error("DEBUG: Error reading saved properties: {}", e.getMessage());
		}
	}

	// Getter method
	public List<String> getAvailablePlatforms() {
		return availablePlatforms;
	}

	// Setter method
	public void setAvailablePlatforms(List<String> platforms) {
		this.availablePlatforms = platforms;
		// Also update the fops list if it's empty
		if (fops == null || fops.isEmpty()) {
			fops = platforms;
		}
	}
}