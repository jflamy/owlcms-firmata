package app.owlcms.firmata.mqtt;

import org.eclipse.paho.client.mqttv3.MqttConnectOptions;
import org.eclipse.paho.client.mqttv3.MqttException;
import org.eclipse.paho.client.mqttv3.MqttPersistenceException;
import org.slf4j.LoggerFactory;

import app.owlcms.firmata.data.DeviceConfig;
import app.owlcms.firmata.data.MQTTConfig;
import app.owlcms.firmata.eventhandlers.OutputEventHandler;
import app.owlcms.firmata.refdevice.RefDevice;
import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;

/**
 * This class receives and emits MQTT events for individual devices.  The actual processing is done in a callback class.
 *
 * Events initiated by the devices start with topics that name the device (owlcms/jurybox)
 * 
 * Devices do not listen to other devices.
 * 
 * A monitor listen to MQTT events that come from a single field of play. These events are of the form (owlcms/fop). The field
 * of play is always the last element in the topic.
 *
 * @author Jean-François Lamy
 */
public class FopMQTTMonitor extends AbstractMQTTMonitor {

	private static final String OWLCMS_FOP = "owlcms/fop/#";
	RefDevice board;
	boolean closed;
	OutputEventHandler emitDefinitionHandler;
	Logger logger = (Logger) LoggerFactory.getLogger(FopMQTTMonitor.class);
	// Add device config field to track which device this monitor is for
	private DeviceConfig deviceConfig;

	public FopMQTTMonitor(String fopName, OutputEventHandler emitDefinitionHandler, RefDevice board,
	        DeviceConfig config) {
		logger.setLevel(Level.DEBUG);
		this.setName(fopName);
		this.setSubscription(OWLCMS_FOP);
		this.board = board;
		this.emitDefinitionHandler = emitDefinitionHandler;
		this.deviceConfig = config;  // Store the device config
		this.start(fopName);
	}

	/**
	 * Get the current platform name from MQTTConfig instead of stored name
	 * This ensures we always use the most up-to-date platform selection
	 */
	public String getCurrentPlatform() {
		return MQTTConfig.getCurrent().getFop();
	}

	public void publishMqttMessageForFop(String topic, String message) throws MqttException, MqttPersistenceException {
		// Use current platform instead of stored name
		String currentPlatform = getCurrentPlatform();
		if (currentPlatform != null) {
			topic = topic + "/" + currentPlatform;
			publishMqttMessage(topic, message);
		} else {
			logger.warn("Cannot publish MQTT message - no platform selected");
		}
	}

	@Override
	protected MqttConnectOptions setupMQTTClient(String userName, String password) {
		MqttConnectOptions connOpts = setUpConnectionOptions(userName != null ? userName : "",
		        password != null ? password : "");
		client.setCallback(new FopMQTTCallback(this, emitDefinitionHandler, board));
		return connOpts;
	}

	/**
	 * Get device information for logging
	 */
	@Override
	protected String getDeviceInfo() {
	    // If we have a device config, use its information
	    if (board != null) {
	        return (deviceConfig != null && deviceConfig.getDeviceTypeName() != null) ? 
	            deviceConfig.getDeviceTypeName() + " on " + deviceConfig.getSerialPort() : 
	            "Device on " + (deviceConfig != null ? deviceConfig.getSerialPort() : "unknown port");
	    }
	    return getName() != null ? "Platform " + getName() : "Unknown device";
	}

	@Override
	protected String getDeviceIdentifier() {
	    if (deviceConfig != null) {
	        return deviceConfig.getDeviceTypeName() + " on " + deviceConfig.getSerialPort();
	    }
	    return getName() != null ? "Platform " + getName() : "Unknown device";
	}

}
