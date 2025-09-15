package app.owlcms.firmata.ui;

import java.io.IOException;
import java.io.InputStream;
import java.util.function.Consumer;

import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.firmata4j.IODevice;
import org.firmata4j.firmata.FirmataDevice;
import org.firmata4j.transport.JSerialCommTransport;
import org.slf4j.LoggerFactory;

import app.owlcms.firmata.data.DeviceConfig;
import app.owlcms.firmata.data.MQTTConfig;
import app.owlcms.firmata.mqtt.FopMQTTMonitor;
import app.owlcms.firmata.refdevice.EventListener;
import app.owlcms.firmata.refdevice.RefDevice;
import app.owlcms.firmata.refdevice.SpecReader;
import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;

public class FirmataService {

	static final Logger logger = (Logger) LoggerFactory.getLogger(FirmataService.class);
	private Runnable confirmationCallback;
	private Consumer<Throwable> errorCallback;
	private RefDevice board;
	private String serialPort;
	private DeviceConfig config;
	private boolean running = false;
	
	private FopMQTTMonitor mqttMonitor; // Add field for monitor

	public FirmataService(DeviceConfig config, Runnable confirmationCallback, Consumer<Throwable> errorCallback) {
		this.confirmationCallback = confirmationCallback;
		this.errorCallback = errorCallback;
		this.config = config;
		logger.setLevel(Level.DEBUG);
	}

	public boolean isRunning() {
		return running;
	}

	public void startDevice() throws Throwable {
		String platform = MQTTConfig.getCurrent().getFop();
		logger.info("starting {} {} {}", config.getDeviceTypeName(), platform, config.getSerialPort());
		String serialPort = this.config.getSerialPort(); // modify for your own computer & setup.
		InputStream is = this.config.getDeviceInputStream();
		MQTTConfig.getCurrent().register(this);

		Thread t1 = new Thread(() -> firmataThread(platform, serialPort, is));
		t1.start();
		running = true;
	}

	private void firmataThread(String fopName, String serialPort, InputStream is) {
		IODevice device = null;
		this.serialPort = serialPort;
		try {
			this.setBoard(null);
			
			// Track if config was already loaded
			boolean configLoaded = false;
			
			try {
				// read configurations
				XSSFWorkbook workbook = new XSSFWorkbook(is);
				var dsr = new SpecReader(fopName);
				dsr.readPinDefinitions(workbook);
				var outputEventHandler = dsr.getOutputEventHandler();
				var inputEventHandler = dsr.getInputEventHandler();
				
				// Only log once
				if (!configLoaded) {
					logger.info("Configuration loaded.");
					configLoaded = true;
				}

				// create the Firmata device and its Board wrapper
				logger.debug("starting firmata device on port {}", serialPort);
				device = new FirmataDevice(new JSerialCommTransport(serialPort));
				logger.info("Device created on port {}", serialPort);
				
				synchronized(this) {
					if (this.getBoard() != null) {
						logger.debug("Board already exists for port {}, not recreating", serialPort);
						return;
					}
					RefDevice board2 = new RefDevice(serialPort, device, outputEventHandler, inputEventHandler);
					board2.init();
					this.setBoard(board2);
					
					// Create or reuse MQTT monitor for this device
					mqttMonitor = FopMQTTMonitor.getOrCreate(fopName, outputEventHandler, getBoard(), config);
				}

				outputEventHandler.handle("fop/startup", "", getBoard());
				device.addEventListener(new EventListener(inputEventHandler, mqttMonitor, getBoard()));
				confirmationCallback.run();
				
			} catch (Exception e) {
				logger./**/warn("firmataThread exception {}",e);
				errorCallback.accept(e);
				if (device != null) {
					try {
						logger.info("Stopping device.");
						device.stop();
						this.setBoard(null);
					} catch (IOException e2) {
					}
				}
			}
		} catch (Throwable e) {
			logger.error("Unexpected error in firmataThread", e);
			errorCallback.accept(e);
		}
	}

	public void stopDevice(Runnable confirmation) {
		if (getBoard() != null) {
			logger.info("closing device {}", serialPort);
			getBoard().stop();
			// remove monitor for this device
			FopMQTTMonitor.removeForDevice(this.config);
			running = false;
			if (confirmation != null) {
				confirmation.run();
			}
 		}
	}

	public RefDevice getBoard() {
		return board;
	}

	public void setBoard(RefDevice board) {
		this.board = board;
	}
}
