package app.owlcms.firmata.mqtt;

import org.eclipse.paho.client.mqttv3.MqttConnectOptions;
import org.slf4j.LoggerFactory;

import app.owlcms.firmata.data.MQTTConfig;
import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;

/**
 * Deal with configuration events that concern all platforms
 *
 * @author Jean-François Lamy
 */
public class ConfigMQTTMonitor extends AbstractMQTTMonitor {

	static Logger logger = (Logger) LoggerFactory.getLogger(ConfigMQTTMonitor.class);
	private static final String OWLCMS_CONFIG = "owlcms/fop/config/#";

	public ConfigMQTTMonitor() {
		logger.setLevel(Level.DEBUG);
		this.setName("config");
		this.setSubscription(OWLCMS_CONFIG);
		MQTTConfig.getCurrent().setConfigMqttMonitor(this);
	}

	@Override
	public MqttConnectOptions setupMQTTClient(String userName, String password) {
		MqttConnectOptions connOpts = setUpConnectionOptions(userName != null ? userName : "",
				password != null ? password : "");
		client.setCallback(new ConfigMQTTCallback(this));
		return connOpts;
	}

	// Override getDeviceInfo to provide server configuration information

	@Override
	protected String getDeviceInfo() {
		return "Server Config";
	}

}
