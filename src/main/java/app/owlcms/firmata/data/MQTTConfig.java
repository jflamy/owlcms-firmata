package app.owlcms.firmata.data;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Properties;
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
import app.owlcms.firmata.ui.UIEvent;
import app.owlcms.firmata.utils.LoggerUtils;
import app.owlcms.utils.ResourceWalker;
import ch.qos.logback.classic.Logger;

public class MQTTConfig {
	
	private static Logger logger = (Logger) LoggerFactory.getLogger(MQTTConfig.class);
	private static MQTTConfig current = null;

	
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
		return portToFirmware;
	}

	public void setPortToFirmare(Map<String, String> portToFirmare) {
		this.portToFirmware = portToFirmare;
	}

	public boolean connectedNoPlatform() {
		return getCurrent().isConnected() && (getCurrent().getFop() == null);
	}
	
	public List<SerialPort> getSerialPorts() {
		SerialPort[] ports = SerialPort.getCommPorts();
		return Arrays.asList(ports);
	}
	
	public void saveSettings() {
		Path devicesDir = ResourceWalker.getLocalDirPath();
		Path settings = devicesDir.resolve("settings.properties");
		Properties props = new Properties();
		props.put("mqttServer", mqttServer);
		props.put("mqttPort", mqttPort);
		props.put("mqttUsername", mqttUsername);
		try {
			props.store(Files.newOutputStream(settings, StandardOpenOption.CREATE, StandardOpenOption.WRITE),"owlcms server connection information");
		} catch (IOException e) {
			logger.error("cannot store settings {}",e.getMessage());
		}
	}
	
	public void readSettings() {
		Path devicesDir = ResourceWalker.getLocalDirPath();
		Path settings = devicesDir.resolve("settings.properties");
		try {
			Properties props = new Properties();
			props.load(Files.newInputStream(settings, StandardOpenOption.READ));
			String p = (String) props.get("mqttServer");
			mqttServer = p != null ? p : mqttServer;
			p = (String) props.get("mqttPort");
			mqttPort = p != null ? p : mqttPort;
			p = (String) props.get("mqttUsername");
			mqttUsername = p != null ? p : mqttUsername;
		} catch (IOException e) {
			logger./**/warn("cannot read settings {}",e.getMessage());
		}
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
		for (SerialPort sp : getSerialPorts()) {
			FirmataDevice device = null;
			try {
				String systemPortName = sp.getSystemPortName();
				logger.debug("sp {}", systemPortName);
				device = new FirmataDevice(new JSerialCommTransport(systemPortName));
				device.ensureInitializationIsDone();
				String firmware = device.getFirmware();
				firmware = firmware.replace(".ino", "");
				portToFirmware.put(systemPortName, firmware);
				progressUpdate.accept(++i);
			} catch (Exception e) {
				LoggerUtils.logError(logger, e);
			} finally {
				try {
					if (device != null) {
						device.stop();
					}
				} catch (IOException e1) {
					LoggerUtils.logError(logger, e1);
				}
			}
		}
		uiEventBus.post(new UIEvent.ConfigsUpdated());
		return portToFirmware;
	}

}