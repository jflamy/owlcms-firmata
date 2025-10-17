package app.owlcms.firmata.ui;

import java.io.IOException;
import java.net.Socket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Map.Entry;
import java.util.TreeMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.eclipse.paho.client.mqttv3.MqttException;
import org.slf4j.LoggerFactory;

import com.fazecast.jSerialComm.SerialPort;
import com.google.common.eventbus.Subscribe;
import com.vaadin.flow.component.AttachEvent;
import com.vaadin.flow.component.Component;
import com.vaadin.flow.component.Html;
import com.vaadin.flow.component.Key;
import com.vaadin.flow.component.Text;
import com.vaadin.flow.component.UI;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.button.ButtonVariant;
import com.vaadin.flow.component.combobox.ComboBox;
import com.vaadin.flow.component.confirmdialog.ConfirmDialog;
import com.vaadin.flow.component.formlayout.FormLayout;
import com.vaadin.flow.component.formlayout.FormLayout.ResponsiveStep;
import com.vaadin.flow.component.formlayout.FormLayout.ResponsiveStep.LabelsPosition;
import com.vaadin.flow.component.html.Div;
import com.vaadin.flow.component.html.H2;
import com.vaadin.flow.component.html.H3;
import com.vaadin.flow.component.html.Hr;
import com.vaadin.flow.component.html.NativeLabel;
import com.vaadin.flow.component.html.Paragraph;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.notification.Notification;
import com.vaadin.flow.component.notification.Notification.Position;
import com.vaadin.flow.component.orderedlayout.HorizontalLayout;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.component.progressbar.ProgressBar;
import com.vaadin.flow.component.textfield.PasswordField;
import com.vaadin.flow.component.textfield.TextField;
import com.vaadin.flow.router.PreserveOnRefresh;
import com.vaadin.flow.router.Route;

import app.owlcms.firmata.data.DeviceConfig;
import app.owlcms.firmata.data.MQTTConfig;
import app.owlcms.firmata.data.SafeEventBusRegistration;
import app.owlcms.firmata.mqtt.ConfigMQTTMonitor;
import app.owlcms.firmata.utils.LoggerUtils;
import app.owlcms.utils.Resource;
import app.owlcms.utils.ResourceWalker;
import ch.qos.logback.classic.Logger;
import app.owlcms.firmata.ui.events.UIEvent;
import java.util.Objects;

@PreserveOnRefresh
@Route("")
public class MainView extends VerticalLayout implements SafeEventBusRegistration {
	private static ConfigMQTTMonitor configMonitor;
	private static final String SECTION_MARGIN_TOP = "0em";
	FormLayout form = new FormLayout();
	private ArrayList<String> availableConfigFiles;
	private ComboBox<Resource> configSelect;
	private ProgressBar deviceDetectionProgress;
	private HorizontalLayout deviceDetectionWait;
	private Div devicesDiv;
	private Html deviceSelectionExplanation;
	private ExecutorService executor = Executors.newSingleThreadExecutor();
	private Div failedConnectionWarning;
	private Paragraph fullyConnectedWarning;
	private Logger logger = (Logger) LoggerFactory.getLogger(MainView.class);
	private ComboBox<String> platformField;
	private Paragraph platformSelectionWarning;
	private Div portsDiv;
	private Div platformDiv = new Div();
	private Button connectButton;
	private Button disconnectButton;
	private boolean connectionAttemptInProgress = false;

	// Map to store device UI components for each port/device
	private Map<String, DeviceUIComponents> deviceUIComponentsMap = new HashMap<>();

	// Class to store UI components for each device
	private static class DeviceUIComponents {
		Button startButton;
		Button stopButton;

		public DeviceUIComponents(Button startButton, Button stopButton) {
			this.startButton = startButton;
			this.stopButton = stopButton;
		}
	}

	public MainView() {
		this.setMargin(true);
		this.setPadding(true);
		this.getStyle().set("margin", "1em");
		setWidth("1000px");
		form.setResponsiveSteps(new ResponsiveStep("0px", 1, LabelsPosition.ASIDE));
		var title = new HorizontalLayout(new H2("owlcms Refereeing Device Control"), new Span(" version " + Main.version));
		title.setAlignItems(Alignment.BASELINE);
		title.getStyle().set("margin-top", "0.5em");
		add(title);

		// Reuse existing static configMonitor if already running to avoid multiple
		// clients using the same clientId (which would kick each other off the broker).
		try {
			if (configMonitor == null || !configMonitor.isConnected()) {
				if (configMonitor != null) {
					try { configMonitor.close(); } catch (Exception ignore) {}
				}
				configMonitor = new ConfigMQTTMonitor();
			} else {
				logger.debug("Reusing existing configMonitor instance");
			}
			MQTTConfig.getCurrent().setConfigMqttMonitor(configMonitor);
		} catch (Throwable t) {
			logger.warn("Failed to initialize or reuse configMonitor", t);
			configMonitor = new ConfigMQTTMonitor();
			MQTTConfig.getCurrent().setConfigMqttMonitor(configMonitor);
		}

		// Create warning messages for later use
		createMessages();

		// Create and show the server connection UI first
		showServerConfig();

		// Try to connect to server directly - this will trigger platform updates through events
		autoConnectOrScan();

		// Create UI for platform selection and device display (data will be populated via events)
		showPlatformSelection();

		// Show device section UI but don't start detection yet - it will happen after platform is selected
		showDeviceSectionUI();
	}

	@Override
	protected void onAttach(AttachEvent e) {
		// Register with event bus after UI is fully attached
		this.uiEventBusRegister();
	}

	// --- Auto-connect or scan for MQTT server ---
	private void autoConnectOrScan() {
		// Prevent multiple simultaneous connection attempts
		if (connectionAttemptInProgress) {
			logger.info("Connection attempt already in progress, skipping");
			return;
		}
		connectionAttemptInProgress = true;
		try {
			// For auto-connect at startup, read settings first
			MQTTConfig.getCurrent().readSettings();
			logger.info("Auto-connect using stored server address: {}", MQTTConfig.getCurrent().getMqttServer());
			
			UI ui = UI.getCurrent();
			if (ui != null) {
				// Schedule handleAutoConnection and then the flag reset in the UI thread
				ui.access(() -> {
					handleAutoConnection();
					// Note: if handleAutoConnection calls tryConnectionSequence, 
					// the sequence might complete after this flag is reset.
					// This is generally okay as the flag prevents *new* top-level attempts.
				});
			} else {
				// No UI case
				handleAutoConnection();
			}
		} catch (Exception e) {
			// Catch errors from readSettings or initial setup before handleAutoConnection
			logger.error("Error during autoConnectOrScan setup phase: {}", e.getMessage(), e);
			// Fallback to trying connection sequence with potentially loaded server config
			String server = MQTTConfig.getCurrent().getMqttServer();
			if (server != null && !server.isEmpty()) {
				logger.info("Setup failed, trying connection sequence with stored server: {}", server);
				tryConnectionSequence(server);
			} else {
				logger.warn("Setup failed and no server configured to attempt fallback sequence.");
			}
		} finally {
			// This finally block ensures the flag is reset after the initial phase of autoConnectOrScan.
			// If tryConnectionSequence was called, it runs independently of this specific flag instance.
			connectionAttemptInProgress = false;
		}
	}

	// Add separate method for auto-connection (startup)
	private void handleAutoConnection() { // Does not manage connectionAttemptInProgress directly
		try {
			logger.info("Auto-connecting to stored MQTT server: {}", MQTTConfig.getCurrent().getMqttServer());
			
			configMonitor.quickCheckConnection();
			configMonitor.start("config");

			if (MQTTConfig.getCurrent().isConnected()) {
				logger.info("Auto-connection successful");
				UI.getCurrent().access(() -> { // Ensure UI updates are in UI thread
					connectButton.removeThemeVariants(ButtonVariant.LUMO_PRIMARY);
					disconnectButton.addThemeVariants(ButtonVariant.LUMO_PRIMARY);
					messageConnected();
				});
				MQTTConfig.getCurrent().saveSettings(); // Save settings on successful auto-connection
				logger.info("Config saved after successful auto-connection.");
				requestPlatformsWithRetry();
			} else {
				logger.warn("Auto-connection failed (isConnected is false). Falling back to connection sequence.");
				UI.getCurrent().access(this::messageNotConnected);
				tryConnectionSequence(MQTTConfig.getCurrent().getMqttServer());
			}
			// Trigger UI refresh for device configs
			eventDeviceConfigs(new UIEvent.ConfigsUpdated());
		} catch (org.eclipse.paho.client.mqttv3.MqttSecurityException e) {
			// Security exception (bad username/password) - show error and let user correct
			logger.error("MQTT security exception: {}", e.getMessage());
			UI.getCurrent().access(() -> {
				messageSecurityError(e.getMessage());
			});
			eventDeviceConfigs(new UIEvent.ConfigsUpdated()); // Refresh UI after error
		} catch (Exception e1) {
			logger.warn("Auto-connect direct attempt failed: {}. Falling back to connection sequence.", e1.getMessage());
			UI.getCurrent().access(this::messageConnectionError); // Show error before sequence
			tryConnectionSequence(MQTTConfig.getCurrent().getMqttServer());
			eventDeviceConfigs(new UIEvent.ConfigsUpdated()); // Refresh UI after sequence attempt
		}
	}

	private void tryConnectionSequence(String failedServerAddress) {
		// This method is part of an ongoing connection attempt, so it doesn't manage connectionAttemptInProgress itself.
		logger.info("Connection to {} failed. Starting fallback connection sequence.", failedServerAddress);

		// 1. Try localhost
		logger.info("Fallback Step 1: Trying localhost (127.0.0.1)");
		if (isMqttReachable("127.0.0.1", MQTTConfig.getCurrent().getMqttPort())) {
			logger.info("MQTT server reachable at localhost (127.0.0.1)");
			UI ui = UI.getCurrent();
			if (ui != null) {
				ui.access(() -> {
					ConfirmDialog dialog = new ConfirmDialog();
					dialog.setHeader("MQTT Server Found at Localhost");
					dialog.setText("Could not connect to " + failedServerAddress +
						". An MQTT server is available at 127.0.0.1. Do you want to use this address?");
					dialog.setCancelable(true);
					dialog.setConfirmText("Yes, use 127.0.0.1");
					dialog.setCancelText("No");
					dialog.addConfirmListener(event -> {
						MQTTConfig.getCurrent().setMqttServer("127.0.0.1");
						mqttServerField.setValue("127.0.0.1");
						MQTTConfig.getCurrent().saveSettings();
						handleMQTTConnection(); // Attempt full connection with localhost
					});
					dialog.addCancelListener(event -> trySubnetScan(failedServerAddress)); // User said no, try scanning
					dialog.open();
				});
			} else {
				// No UI, cannot confirm. Proceed to scan.
				trySubnetScan(failedServerAddress);
			}
			return; // Dialog callbacks will continue the process
		}

		// Localhost not reachable or no UI to confirm, proceed to scan
		trySubnetScan(failedServerAddress);
	}

	private void trySubnetScan(String failedServerAddress) {
		// This method is part of an ongoing connection attempt.
		logger.info("Fallback Step 2: Scanning subnet for MQTT server.");
		final String subnetToScan = getActiveNetworkSubnet();
		final String subnet = subnetToScan != null ? subnetToScan : getSubnet(failedServerAddress);

		if (subnet != null && subnet.endsWith(".")) {
			int hostCount = getSubnetHostCount(subnet);
			if (hostCount > 256) { // Equivalent to /24 or smaller network for typical IPv4
				logger.warn("Network too large for scanning: {} hosts on subnet starting with {}", hostCount, subnet);
				UI ui = UI.getCurrent();
				if (ui != null) {
					ui.access(() -> errorNotification(
						"Network (subnet " + subnet + "...) too large for automatic scanning (" + hostCount
							+ " hosts). Please enter server address manually."));
				}
				return; // End of this path for tryConnectionSequence
			}

			logger.info("Scanning for MQTT server on subnet {} (estimated {} hosts)", subnet, hostCount);
			UI ui = UI.getCurrent();
			if (ui != null) {
				ui.access(() -> Notification.show("Scanning for MQTT server on subnet " + subnet + "...",
					3000, Position.TOP_END));
			}

			String foundIp = scanForMQTTServer(subnet, MQTTConfig.getCurrent().getMqttPort(), 100);
			UI ui2 = UI.getCurrent(); // Re-fetch UI instance
			if (ui2 != null) {
				ui2.access(() -> {
					if (foundIp != null) {
						logger.info("Found MQTT server by scan at: {}", foundIp);
						ConfirmDialog dialog = new ConfirmDialog();
						dialog.setHeader("MQTT Server Found by Scan");
						dialog.setText("Could not connect to " + failedServerAddress +
							". A MQTT server was found by network scan at " + foundIp +
							". Do you want to update the configuration and connect?");
						dialog.setCancelable(true);
						dialog.setConfirmText("Yes, use " + foundIp);
						dialog.setCancelText("No");
						dialog.addConfirmListener(event -> {
							MQTTConfig.getCurrent().setMqttServer(foundIp);
							mqttServerField.setValue(foundIp);
							MQTTConfig.getCurrent().saveSettings();
							handleMQTTConnection(); // Attempt full connection with the found IP
						});
						// If user cancels, no further automatic steps.
						dialog.open();
					} else {
						logger.warn("No MQTT server found on subnet {}", subnet);
						errorNotification("No MQTT server found on subnet " + subnet +
							". Please check the server address and ensure the MQTT server is running.");
					}
				});
			}
		} else {
			logger.warn("Cannot determine subnet for scanning based on failed address: '{}' or active interfaces.",
				failedServerAddress);
			UI ui = UI.getCurrent();
			if (ui != null) {
				ui.access(() -> errorNotification(
					"Cannot determine network subnet for scanning. Please enter the correct server address manually."));
			}
		}
	}

	// Helper: get subnet from IP string (e.g. "192.168.1.10" -> "192.168.1.")
	private String getSubnet(String ip) {
		if (ip == null)
			return null;
		try {
			String[] parts = ip.split("\\.");
			if (parts.length == 4) {
				return parts[0] + "." + parts[1] + "." + parts[2] + ".";
			}
		} catch (Exception e) {
			logger.debug("Could not parse IP address: {}", ip);
		}
		return null;
	}

	// Helper: get active network subnet
	private String getActiveNetworkSubnet() {
		try {
			// Get all network interfaces
			for (java.net.NetworkInterface iface : java.util.Collections.list(java.net.NetworkInterface.getNetworkInterfaces())) {
				// Skip loopback, inactive, virtual interfaces etc
				if (iface.isLoopback() || !iface.isUp() || iface.isVirtual() || iface.isPointToPoint()) {
					continue;
				}

				// Look for IPv4 addresses
				for (java.net.InterfaceAddress addr : iface.getInterfaceAddresses()) {
					java.net.InetAddress inetAddr = addr.getAddress();
					if (inetAddr instanceof java.net.Inet4Address) {
						String hostAddress = inetAddr.getHostAddress();
						logger.info("Found active network interface: {} with address: {}",
						        iface.getDisplayName(), hostAddress);
						return getSubnet(hostAddress);
					}
				}
			}
		} catch (Exception e) {
			logger.error("Error determining network interface: {}", e.getMessage());
		}
		return null;
	}

	// Helper: estimate host count for /24 subnet - changed to handle different subnet masks
	private int getSubnetHostCount(String ip) {
		if (ip == null) return -1;
		try {
			for (java.net.NetworkInterface iface : java.util.Collections.list(java.net.NetworkInterface.getNetworkInterfaces())) {
				for (java.net.InterfaceAddress addr : iface.getInterfaceAddresses()) {
					java.net.InetAddress inetAddr = addr.getAddress();
					if (inetAddr instanceof java.net.Inet4Address) {
						String hostAddress = inetAddr.getHostAddress();
						if (hostAddress.startsWith(ip.substring(0, ip.length()-1))) {
							// Calculate hosts from network prefix length
							short prefixLength = addr.getNetworkPrefixLength();
							return (int) Math.pow(2, 32 - prefixLength);
						}
					}
				}
			}
		} catch (Exception e) {
			logger.debug("Error calculating subnet size: {}", e.getMessage());
		}
		// Default to /24 if we can't determine
		return 256;
	}

	// Helper: scan subnet for MQTT server (returns first found IP or null)
	private String scanForMQTTServer(String subnet, String portStr, int timeoutMs) {
		int parsedPort;
		try {
			parsedPort = Integer.parseInt(portStr);
		} catch (Exception e) {
			parsedPort = 1883;
		}
		final int port = parsedPort;
		AtomicBoolean found = new AtomicBoolean(false);
		String[] result = new String[1];
		List<Thread> threads = new ArrayList<>();
		
		logger.info("Starting subnet scan for MQTT server on {}1-254 port {}", subnet, port);
		
		for (int i = 1; i < 255; i++) {
			final String host = subnet + i;
			Thread t = new Thread(() -> {
				if (found.get())
					return;
				try (Socket socket = new Socket()) {
					socket.connect(new java.net.InetSocketAddress(host, port), timeoutMs);
					if (!found.getAndSet(true)) {
						result[0] = host;
						logger.info("Found MQTT server at {}", host);
					}
				} catch (Exception e) {
					// ignore connection failures during scan
				}
			});
			threads.add(t);
			t.start();
		}
		
		// Wait for all threads to complete or first success
		for (Thread t : threads) {
			try {
				t.join(timeoutMs + 100);
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
			}
			if (found.get())
				break;
		}
		
		// Interrupt remaining threads if we found a server
		if (found.get()) {
			for (Thread t : threads) {
				if (t.isAlive()) {
					t.interrupt();
				}
			}
		}
		
		logger.info("Subnet scan completed. Result: {}", result[0] != null ? result[0] : "No server found");
		return result[0];
	}

	// Helper: check if MQTT is reachable at given address/port
	private boolean isMqttReachable(String host, String portStr) {
		int port = 1883;
		try {
			port = Integer.parseInt(portStr);
		} catch (Exception e) {
		}
		try (Socket socket = new Socket()) {
			socket.connect(new java.net.InetSocketAddress(host, port), 300);
			return true;
		} catch (Exception e) {
			return false;
		}
	}

	@Subscribe
	public void eventDeviceConfigs(UIEvent.ConfigsUpdated cu) {
		logger.debug("Received ConfigsUpdated event: {}", System.identityHashCode(cu));
		UI ui = UI.getCurrent();
		if (ui != null) {
			ui.access(() -> {
				// Update connection status messages only - don't rebuild device UI here
				// since it's already been built in detectDevices
				if (MQTTConfig.fullyConnected()) {
					messageConnected();
				} else if (MQTTConfig.getCurrent().connectedNoPlatform()) {
					messageNoPlatform();
				}
				
				logger.debug("ConfigsUpdated event processed, device count: {}", 
					MQTTConfig.getCurrent().getPortToConfig().size());
			});
		}
	}

	private void showNoDevicesMessage() {
		Paragraph noDevices = new Paragraph(
			"No devices detected. Please check your connections and try again.");
		noDevices.getStyle().set("font-style", "italic");
		portsDiv.add(noDevices);
		logger.info("No devices were detected");
	}

	private void createMessages() {
		fullyConnectedWarning = new Paragraph("""
		                                      \u26a0 You cannot start devices until you have connected to the server and selected a platform.
		                                      """);
		fullyConnectedWarning.getStyle().set("font-weight", "bold");
		fullyConnectedWarning.getStyle().set("margin", "0");
		failedConnectionWarning = new Div();
		failedConnectionWarning.getStyle().set("margin", "0");
		failedConnectionWarning.getStyle().set("font-weight", "bold");
		platformSelectionWarning = new Paragraph("""
		                                         \u26a0 You must be connected to the server to select the platform.
		                                         """);
		platformSelectionWarning.getStyle().set("margin", "0");
		platformSelectionWarning.getStyle().set("font-weight", "bold");
	}

	private Hr createSeparator() {
		var hr = new Hr();
		hr.getStyle().set("height", "3px");
		return hr;
	}

	@SuppressWarnings("unused")
	private void createSerialCombo(DeviceConfig deviceConfig) {
		ComboBox<SerialPort> serialCombo = new ComboBox<>();
		serialCombo.setPlaceholder("Select Port");

		List<SerialPort> serialPorts = MQTTConfig.getCurrent().getSerialPorts();
		serialCombo.setItems(serialPorts);
		serialCombo.setItemLabelGenerator((i) -> i.getSystemPortName());
		serialCombo.setValue(serialPorts.size() > 0 ? serialPorts.get(0) : null);
		serialCombo.addThemeName("bordered");
		serialCombo.addValueChangeListener(e -> deviceConfig.setSerialPort(e.getValue().getSystemPortName()));
	}

	// @SuppressWarnings("unused")
	// private void createUploadButton(DeviceConfig deviceConfig, Upload upload) {
	// UploadI18N i18n = new UploadI18N();
	// i18n.setUploading(
	// new Uploading().setError(new Uploading.Error().setUnexpectedServerError("File could not be loaded")))
	// .setAddFiles(new AddFiles().setOne("Upload Device Configuration"));
	// upload.setDropLabel(new Span("Configuration files are copied to the installation directory"));
	// upload.setI18n(i18n);
	// upload.addStartedListener(event -> {
	// logger.error("started {}" + event.getFileName());
	// });
	// upload.addSucceededListener(e -> {
	// upload.clearFileList();
	// deviceConfig.setDeviceTypeName(e.getFileName());
	// });
	// upload.addFailedListener(e -> {
	// logger.error("failed upload {}", e.getReason());
	// ConfirmDialog dialog = new ConfirmDialog();
	// dialog.setHeader("Upload Failed");
	// dialog.setText(new Html("<p>" + e.getReason().getLocalizedMessage() + "</p>"));
	// dialog.setConfirmText("OK");
	// dialog.open();
	// upload.clearFileList();
	// });
	// upload.addFileRejectedListener(event -> {
	// logger.error("rejected {}" + event.getErrorMessage());
	// });
	// }

	private void detectDevices(UI ui, boolean showProgress) {
		logger.debug("detectDevices {} showProgress={}", ui, showProgress);
		List<SerialPort> serialPorts = MQTTConfig.getCurrent().getSerialPorts();

		logger.info("Starting device detection with {} ports", serialPorts.size());

		// Always show progress indicator when explicitly requested
		if (showProgress) {
			ui.access(() -> {
				deviceDetectionWait.setVisible(true);
				deviceDetectionProgress.setVisible(true);
				deviceDetectionProgress.setIndeterminate(false);
				deviceDetectionProgress.setValue(0);
				// Clear any existing "no devices" message while detecting
				portsDiv.removeAll();
				logger.debug("Progress indicator shown, cleared existing UI");
			});
		}

		// Clear existing device mapping
		MQTTConfig.getCurrent().getPortToConfig().clear();
		MQTTConfig.getCurrent().getPortToFirmware().clear();
		logger.debug("Cleared existing device configs");

		try {
			// Build port to firmware map - this does the actual device detection
			logger.info("Starting buildPortToFirmwareMap");
			Map<String, String> detectedFirmware = MQTTConfig.getCurrent().buildPortToFirmwareMap(
					step -> {
				        logger.debug("Detection progress: step {} of {}", step, serialPorts.size() + 1);
				        if (showProgress) {
					        // Calculate progress value with proper bounds
					        float value = serialPorts.isEmpty() ? 1.0f : ((float) step) / (serialPorts.size() + 1);
					        value = Math.max(0, Math.min(1.0f, value));
					        final float finalValue = value;

					        ui.access(() -> {
						        deviceDetectionProgress.setValue(finalValue);
					        });
				        }
			        });
			
			// Create new TreeMap for atomic update
			TreeMap<String, DeviceConfig> newDeviceConfigs = new TreeMap<>();
			
			// Build new device configs from detected firmware
			for (Entry<String, String> pf : detectedFirmware.entrySet()) {
				logger.info("Detected device: port={}, type={}", pf.getKey(), pf.getValue());
				newDeviceConfigs.put(pf.getKey(), 
						new DeviceConfig(pf.getKey(), pf.getValue()));
			}
			
			// Atomic update of device configs
			synchronized (MQTTConfig.getCurrent().getPortToConfig()) {
				MQTTConfig.getCurrent().getPortToConfig().clear();
				MQTTConfig.getCurrent().getPortToConfig().putAll(newDeviceConfigs);
			}

			logger.info("Device detection complete. Found {} devices", newDeviceConfigs.size());

			// Update UI immediately in this thread to ensure proper sequencing
			ui.access(() -> {
				logger.debug("Hiding progress indicator and updating UI with detected devices");
				deviceDetectionWait.setVisible(false);
				
				// Force immediate UI update
				portsDiv.removeAll();
				deviceUIComponentsMap.clear();
				
				if (newDeviceConfigs.isEmpty()) {
					showNoDevicesMessage();
				} else {
					// Show devices directly here to ensure they're displayed
					for (DeviceConfig dc : newDeviceConfigs.values()) {
						showDeviceConfig(dc, MQTTConfig.getCurrent().getFop());
					}
					logger.info("Displayed {} devices in UI", newDeviceConfigs.size());
					
					// Auto-start devices when fully connected
					if (MQTTConfig.fullyConnected()) {
						autoStartDevices(ui);
					}
				}
				
				// Post the event after UI is updated
				MQTTConfig.getCurrent().getUiEventBus().post(new UIEvent.ConfigsUpdated());
			});

		} catch (Exception e) {
			logger.error("Error during device detection: {}", e.getMessage(), e);
			ui.access(() -> {
				deviceDetectionWait.setVisible(false);
				errorNotification("Device detection failed: " + e.getMessage());
				// Show "no devices" message after failed detection
				if (portsDiv != null) {
					portsDiv.removeAll();
					showNoDevicesMessage();
				}
			});
		}
	}

	private void errorNotification(String errorMessage) {
		ConfirmDialog dialog = new ConfirmDialog();
		dialog.setHeader("Error");
		dialog.setText(errorMessage);
		dialog.setConfirmText("OK");
		dialog.setCancelable(false); // Force user to click OK
		dialog.setCloseOnEsc(false); // Prevent closing with Esc key
		dialog.open();
	}

	private Collection<String> getAvailableConfigNames() {
		availableConfigFiles = new ArrayList<String>();
		try (Stream<Path> stream = Files.list(ResourceWalker.getLocalDirPath())) {
			return keepConfigNames(stream);
		} catch (IOException e) {
			LoggerUtils.logError(logger, e);
		}
		return availableConfigFiles;
	}

	private Collection<String> keepConfigNames(Stream<Path> stream) {
		return stream
		        .filter(file -> !Files.isDirectory(file))
		        .map(Path::getFileName)
		        .map(Path::toString)
		        .filter(s -> s.endsWith(".xlsx"))
		        .map(s -> s.replace(".xlsx", ""))
		        .collect(Collectors.toSet());
	}

	private void messageConnected() {
		fullyConnectedWarning.setVisible(false);
		failedConnectionWarning.setVisible(false);
		platformSelectionWarning.setVisible(false);
	}

	private void messageConnectionError() {
		failedConnectionWarning.setText("""
		                                \u26a0 Cannot connect to server. Please check the address and port and that the server is running.
		                                """);
		failedConnectionWarning.setVisible(true);
		fullyConnectedWarning.setVisible(true);
		platformSelectionWarning.setText("""
		                                 \u26a0 Please connect to the server first.
		                                 """);
		platformSelectionWarning.setVisible(true);
	}

	private void messageSecurityError(String errorMessage) {
		String message = "\u26a0 MQTT Connection Failed";
		if (errorMessage != null && !errorMessage.isEmpty()) {
			message += ": " + errorMessage;
		}
		failedConnectionWarning.setText(message);
		failedConnectionWarning.setVisible(true);
		fullyConnectedWarning.setVisible(true);
		platformSelectionWarning.setText("""
		                                 \u26a0 Please check your MQTT server settings and try again.
		                                 """);
		platformSelectionWarning.setVisible(true);
	}

	private void messageNoPlatform() {
		failedConnectionWarning.setVisible(false);
		;
		fullyConnectedWarning.setVisible(true);
		platformSelectionWarning.setText("""
		                                 \u26a0 Multiple platforms detected, please select one.
		                                 """);
		platformSelectionWarning.setVisible(true);
	}

	private void messageNotConnected() {
		failedConnectionWarning.setText("""
		                                \u26a0 Not connected to server.
		                                 """);
		failedConnectionWarning.setVisible(true);
		fullyConnectedWarning.setVisible(true);
		platformSelectionWarning.setText("""
		                                 \u26a0 Please connect to the server first.
		                                 """);
		platformSelectionWarning.setVisible(true);
	}

	private void reportError(Throwable ex, UI ui) {
		logger.error("could not start {}", ex.toString());
		ui.access(() -> {
			ConfirmDialog dialog = new ConfirmDialog();
			String errorMessage;
			
			// Check if this is a security exception
			if (ex instanceof org.eclipse.paho.client.mqttv3.MqttSecurityException ||
			    (ex.getCause() != null && ex.getCause() instanceof org.eclipse.paho.client.mqttv3.MqttSecurityException)) {
				dialog.setHeader("MQTT Connection Failed");
				errorMessage = ex.getMessage();
				if (errorMessage == null || errorMessage.isEmpty()) {
					errorMessage = ex.getCause() != null ? ex.getCause().getMessage() : ex.toString();
				}
				if (errorMessage == null || errorMessage.isEmpty()) {
					errorMessage = "Authentication or connection error. Please check your MQTT server settings.";
				}
			} else {
				dialog.setHeader("Device Initialization Failed");
				errorMessage = ex.getCause() != null ? ex.getCause().getMessage() : ex.getMessage();
				if (errorMessage == null || errorMessage.isEmpty()) {
					errorMessage = ex.toString();
				}
			}
			
			dialog.setText(new Html("<p>" + errorMessage + "</p>"));
			dialog.setConfirmText("OK");
			dialog.open();
		});
	}

	private void showDeviceConfig(DeviceConfig deviceConfig, String platform) {
		HorizontalLayout dcl = new HorizontalLayout();

		Button stop = new Button("Stop Device");
		Button start = new Button("Start Device");

		// Store UI components for this device to allow auto-start to update UI
		deviceUIComponentsMap.put(deviceConfig.getSerialPort(),
		        new DeviceUIComponents(start, stop));

		configSelect = new ComboBox<Resource>();
		configSelect.setPlaceholder("No configuration selected");
		configSelect.setHelperText("Select a configuration");
		String string = ResourceWalker.getLocalDirPath().toString();
		// logger.debug("menu items from directory {}", string);
		List<Resource> resourceList = new ResourceWalker().getResourceList(string,
		        ResourceWalker::relativeName, null, Locale.getDefault(), true);
		// only show xlsx
		configSelect.setItems(resourceList.stream()
		        .filter(r -> r.getFileName().endsWith(".xlsx")).collect(Collectors.toList()));

		// Check if the device type has a matching config file
		Resource curResource = null;
		if (deviceConfig.getDeviceTypeName() != null) {
			curResource = resourceList.stream()
			        .filter(r -> r.getFileName().contentEquals(deviceConfig.getDeviceTypeName() + ".xlsx"))
			        .findFirst().orElse(null);

			// If found a config, store it directly in the device config
			if (curResource != null) {
				deviceConfig.setDevice(curResource.toString());
			}
		}

		configSelect.setValue(curResource);
		if (MQTTConfig.fullyConnected()) {
			boolean hasConfig = curResource != null;
			boolean isRunning = deviceConfig.getFirmataService() != null &&
			        deviceConfig.getFirmataService().isRunning();

			if (isRunning) {
				// Device is running
				start.setEnabled(false);
				stop.setEnabled(true);
				start.removeThemeVariants(ButtonVariant.LUMO_PRIMARY);
				stop.addThemeVariants(ButtonVariant.LUMO_PRIMARY);
			} else {
				// Device is stopped
				start.setEnabled(hasConfig);
				stop.setEnabled(false);
				if (hasConfig) {
					start.addThemeVariants(ButtonVariant.LUMO_PRIMARY);
				}
				stop.removeThemeVariants(ButtonVariant.LUMO_PRIMARY);
			}
		} else {
			// Not connected to server/platform
			start.setEnabled(false);
			stop.setEnabled(false);
		}
		configSelect.setWidth("15em");
		configSelect.addValueChangeListener(e -> {
			if (!e.isFromClient()) {
				return;
			}
			deviceConfig.setDevice(e.getValue().toString());
			start.setEnabled(true);
			stop.setEnabled(true);
		});

		start.addClickListener(e -> {
			String dev = deviceConfig.getDeviceTypeName();
			if (dev != null) {
				UI ui = UI.getCurrent();
				if (deviceConfig.getFirmataService() != null) {
					deviceConfig.getFirmataService().stopDevice(null);
				}
				deviceConfig.setFirmataService(new FirmataService(deviceConfig, () -> confirmStartOk(ui, start, stop),
				        (ex) -> reportError(ex, ui)));
				try {
					FirmataService firmataService = deviceConfig.getFirmataService();
					firmataService.startDevice();
				} catch (Throwable e1) {
					logger.error("start exception {}", e1);
					String errorMessage = "Configuration file cannot be opened: " + e1.getCause().getMessage();
					errorNotification(errorMessage);
				}
			}
		});
		start.addThemeVariants(ButtonVariant.LUMO_PRIMARY);
		start.addClickShortcut(Key.ENTER);

		stop.addClickListener(e -> {
			UI ui = UI.getCurrent();
			if (deviceConfig.getFirmataService() != null) {
				deviceConfig.getFirmataService().stopDevice(() -> confirmStopOk(ui, start, stop));
			}
		});

		var buttons = new HorizontalLayout(start, stop);
		buttons.getStyle().set("margin-top", "1em");

		Span deviceName = new Span(deviceConfig.getDeviceTypeName());
		deviceName.setWidth("15em");
		dcl.add(new NativeLabel(deviceConfig.getSerialPort()), deviceName, configSelect,
		        buttons);
		dcl.setAlignItems(Alignment.BASELINE);
		portsDiv.add(dcl);

		devicesDiv.add(portsDiv);

	}

	private void showDeviceSectionUI() {
		Button scanButton = new Button("Detect Devices", (e) -> {
			UI ui = UI.getCurrent();
			// Clear existing device information and UI
			if (portsDiv != null) {
				portsDiv.removeAll();
			}
			deviceUIComponentsMap.clear();
			MQTTConfig.getCurrent().closeAll();

			// Start detection with progress indicator visible
			executor.submit(() -> detectDevices(ui, true));
		});

		var deviceSelectionTitle = new HorizontalLayout(
			new H3("Devices"),
			scanButton,
			new Text("Configuration files are located in " + ResourceWalker.getLocalDirPath().toAbsolutePath().toString()));

		deviceSelectionTitle.setAlignItems(Alignment.BASELINE);
		deviceSelectionTitle.getStyle().set("margin-top", SECTION_MARGIN_TOP);

		// Create progress indicator elements
		deviceSelectionExplanation = new Html("""
		                                      <div>Detecting connected devices. <b>Please wait.</b></div>
		                                      """);
		deviceSelectionExplanation.getStyle().set("width", "40em");
		deviceDetectionProgress = new ProgressBar();
		deviceDetectionProgress.setIndeterminate(false);
		deviceDetectionWait = new HorizontalLayout(deviceSelectionExplanation, deviceDetectionProgress);
		deviceDetectionWait.setWidth("40em");
		deviceDetectionWait.setVisible(false); // Initially hidden until detection starts

		// Create container divs
		devicesDiv = new Div();
		portsDiv = new Div();

		// Build the UI hierarchy
		devicesDiv.add(deviceSelectionTitle, deviceDetectionWait, fullyConnectedWarning, portsDiv);
		getAvailableConfigNames();
		add(createSeparator(), devicesDiv);

		// Do not start automatic detection here
	}

	private void showPlatformSelection() {
		add(createSeparator());
		var platformTitle = new H3("Platform");
		platformTitle.getStyle().set("margin-top", SECTION_MARGIN_TOP);
		platformDiv = new Div();

		// Create an empty platform field - will be populated when platforms are received
		platformField = new ComboBox<String>();
		platformField.setWidth("15em");
		platformField.setPlaceholder("Please select a platform");

		platformDiv.add(platformSelectionWarning, platformField);
		add(platformTitle, platformDiv);
	}

	private TextField mqttServerField; // Add field declaration

	private void showServerConfig() {
		connectButton = new Button("Connect");
		disconnectButton = new Button("Disconnect");
		connectButton.addThemeVariants(ButtonVariant.LUMO_PRIMARY);
		if (!MQTTConfig.getCurrent().isConnected()) {
			messageNotConnected();
		}

		// Use the extracted handler for the connect button
		connectButton.addClickListener(e -> handleMQTTConnection());

		disconnectButton.addClickListener(e -> {
			// First stop all running devices and update their UI
			stopAllDevicesOnDisconnect();
			
			// Perform disconnection actions
			try {
				configMonitor.close(); // This is called by MQTTConfig.getCurrent().closeAll()
				MQTTConfig.getCurrent().closeAll(); // This also sets internal connected flags to false
			} catch (Throwable e1) {
				logger.warn("Error during MQTT disconnection: {}", e1.getMessage());
			}

			// Reset connection attempt flag to allow new connections
			connectionAttemptInProgress = false;

			// Update button states to reflect disconnection
			// After disconnection, connect button should be primary, disconnect non-primary
			disconnectButton.removeThemeVariants(ButtonVariant.LUMO_PRIMARY);
			connectButton.addThemeVariants(ButtonVariant.LUMO_PRIMARY);
			
			// Clear platform information and reset UI
			platformField.clear();
			platformField.setItems(new ArrayList<String>());
			
			// Clear device UI since we're disconnected
			if (portsDiv != null) {
				portsDiv.removeAll();
			}
			deviceUIComponentsMap.clear();
			
			// Clear device configurations
			MQTTConfig.getCurrent().getPortToConfig().clear();
			MQTTConfig.getCurrent().getPortToFirmware().clear();

			// Update UI messages and trigger general UI refresh
			messageNotConnected(); // Explicitly set "not connected" message status
			eventDeviceConfigs(new UIEvent.ConfigsUpdated()); // For other dependent UI parts to refresh
		});

		Span buttons = new Span(connectButton, new Text("  "), disconnectButton);
		var mqttConfigTitle = new HorizontalLayout(new H3("MQTT Server"), buttons);
		mqttConfigTitle.setSpacing(true);
		mqttConfigTitle.setAlignItems(Alignment.BASELINE);
		mqttConfigTitle.getStyle().set("margin-top", "0.5em");

		// Read settings first to populate field with stored value
		MQTTConfig.getCurrent().readSettings();
		
		mqttServerField = new TextField(); // Initialize field
		mqttServerField.setHelperText("Usually the address or name of the owlcms server");
		mqttServerField.setValue(MQTTConfig.getCurrent().getMqttServer());
		// Update config when field value changes, but don't auto-connect
		mqttServerField.addValueChangeListener(e -> {
			// Only update config, don't connect automatically
			if (e.isFromClient()) {
				logger.debug("Server field updated to: {}", e.getValue());
			}
		});

		TextField mqttPortField = new TextField();
		mqttPortField.setValue(MQTTConfig.getCurrent().getMqttPort());
		mqttPortField.addValueChangeListener(e -> MQTTConfig.getCurrent().setMqttPort(e.getValue()));

		TextField mqttUsernameField = new TextField();
		mqttUsernameField.setValue(MQTTConfig.getCurrent().getMqttUsername());
		mqttUsernameField.addValueChangeListener(e -> MQTTConfig.getCurrent().setMqttUsername(e.getValue()));

		PasswordField mqttPasswordField = new PasswordField();
		mqttPasswordField.setValue(MQTTConfig.getCurrent().getMqttPassword());
		mqttPasswordField.addValueChangeListener(e -> MQTTConfig.getCurrent().setMqttPassword(e.getValue()));

		form.setResponsiveSteps(
		        // Use one column by default
		        new ResponsiveStep("0", 1),
		        // Use two columns, if layout's width exceeds 500px
		        new ResponsiveStep("500px", 2));

		this.setWidth("1000px");
		addFormItemX(mqttServerField, "MQTT Server");
		addFormItemX(mqttPortField, "MQTT Port");
		addFormItemX(mqttUsernameField, "MQTT Username");
		addFormItemX(mqttPasswordField, "MQTT Password");
		this.getStyle().set("margin-top", "0");
		this.setMargin(false);
		this.setPadding(false);

		this.add(mqttConfigTitle);
		this.add(failedConnectionWarning);
		this.add(form);

		updateServerConfigFromFields(
		        mqttServerField, mqttPortField, mqttUsernameField, mqttPasswordField);
	}

	// Update the handleMQTTConnection method to use the UI field value
	private void handleMQTTConnection() {
		if (connectionAttemptInProgress) { 
			logger.info("handleMQTTConnection: Connection attempt already in progress. Skipping.");
			return;
		}
		connectionAttemptInProgress = true;

		String serverFromUIField = mqttServerField.getValue();
		String initialServerInConfig = MQTTConfig.getCurrent().getMqttServer(); 
		String serverToAttemptConnectionWith;
		boolean uiFieldCausedConfigChangeForAttempt = false;

		if (serverFromUIField != null && !serverFromUIField.trim().isEmpty() &&
		    !serverFromUIField.trim().equals(initialServerInConfig)) {
			serverToAttemptConnectionWith = serverFromUIField.trim();
			MQTTConfig.getCurrent().setMqttServer(serverToAttemptConnectionWith);
			uiFieldCausedConfigChangeForAttempt = true;
			logger.info("Connect button: Will attempt connection with server from UI field: {}", serverToAttemptConnectionWith);
		} else {
			serverToAttemptConnectionWith = initialServerInConfig;
			if (serverFromUIField == null || serverFromUIField.trim().isEmpty()) {
				logger.info("Connect button: UI server field is empty. Using configured server: {}", serverToAttemptConnectionWith);
			} else {
				logger.info("Connect button: UI server field matches configured server. Using: {}", serverToAttemptConnectionWith);
			}
		}

		try {
			if (configMonitor.isConnected() && MQTTConfig.getCurrent().getMqttServer().equals(serverToAttemptConnectionWith)) {
				logger.info("MQTT client already connected to {}. Updating UI.", serverToAttemptConnectionWith);
				connectButton.removeThemeVariants(ButtonVariant.LUMO_PRIMARY);
				disconnectButton.addThemeVariants(ButtonVariant.LUMO_PRIMARY);
				messageConnected();
				requestPlatformsWithRetry();
				// No need to save settings if already connected to the target.
				// eventDeviceConfigs will be called in finally.
				return; 
			}

			logger.info("Establishing new connection to MQTT server: {}", serverToAttemptConnectionWith);
			configMonitor.quickCheckConnection(); 
			configMonitor.start("config");        

			if (MQTTConfig.getCurrent().isConnected()) {
				logger.info("Connection successful to {}", serverToAttemptConnectionWith);
				connectButton.removeThemeVariants(ButtonVariant.LUMO_PRIMARY);
				disconnectButton.addThemeVariants(ButtonVariant.LUMO_PRIMARY);
				messageConnected();
				requestPlatformsWithRetry();
				MQTTConfig.getCurrent().saveSettings(); // Save if successful connection was made
				logger.info("Config saved after successful connection.");
			} else {
				logger.warn("Connection attempt to {} failed (isConnected is false after attempt).", serverToAttemptConnectionWith);
				if (uiFieldCausedConfigChangeForAttempt) {
					MQTTConfig.getCurrent().setMqttServer(initialServerInConfig); 
					logger.info("Reverted server address in config to: {} after failed attempt.", initialServerInConfig);
				}
				messageNotConnected(); 
				tryConnectionSequence(serverToAttemptConnectionWith); 
			}
		} catch (org.eclipse.paho.client.mqttv3.MqttSecurityException e) {
			logger.error("MQTT security exception during connection to {}: {}", serverToAttemptConnectionWith, e.getMessage());
			if (uiFieldCausedConfigChangeForAttempt) {
				MQTTConfig.getCurrent().setMqttServer(initialServerInConfig); 
				logger.info("Reverted server address in config to: {} after security exception.", initialServerInConfig);
			}
			messageSecurityError(e.getMessage());
		} catch (Exception e) {
			logger.warn("Connection attempt to {} failed: {}", serverToAttemptConnectionWith, e.getMessage());
			if (uiFieldCausedConfigChangeForAttempt) {
				MQTTConfig.getCurrent().setMqttServer(initialServerInConfig); 
				logger.info("Reverted server address in config to: {} after exception during connection attempt.", initialServerInConfig);
			}
			messageConnectionError(); // Show error before sequence
			tryConnectionSequence(serverToAttemptConnectionWith); 
		} finally {
			connectionAttemptInProgress = false;
			eventDeviceConfigs(new UIEvent.ConfigsUpdated());
		}
	}

	/**
	 * Request platforms from the server with retry logic
	 */
	private void requestPlatformsWithRetry() {
		// Make multiple attempts to get platforms from server
		int maxAttempts = 3;
		MQTTConfig.getCurrent().setFops(new ArrayList<>()); // reset the list
		for (int attempt = 0; attempt < maxAttempts; attempt++) {
			try {
				// Request configuration from the server
				configMonitor.publishMqttMessage("owlcms/config", "");
				logger.info("Sent config request to server (attempt {})", attempt + 1);

				// Wait a bit to allow response
				Thread.sleep(500);

				// If platforms received, we can break out
				List<String> platforms = MQTTConfig.getCurrent().getFops();
				if (platforms != null && !platforms.isEmpty()) {
					logger.info("Received platforms on attempt {}: {}",
					        attempt + 1, platforms);

					// Store platforms for future use
					MQTTConfig.getCurrent().setAvailablePlatforms(platforms);

					// Only update platform UI if it exists
					UI ui = UI.getCurrent();
					if (ui != null) {
						ui.access(() -> {
							updatePlatforms();
							// Device detection will be triggered from updatePlatforms
						});
					}
					return;
				}
			} catch (MqttException | InterruptedException e) {
				logger.error("Config request error (attempt {}): {}", attempt + 1, e.getMessage());
			}

			logger.warn("No platforms received on attempt {}, will retry...", attempt + 1);
		}

		logger.error("Failed to receive platforms after {} attempts", maxAttempts);

		// Even if no platforms were received, we should still proceed with device detection
		UI ui = UI.getCurrent();
		if (ui != null) {
			ui.access(this::startDeviceDetection);
		}
	}

	private void updateServerConfigFromFields(
	        TextField mqttServerField, TextField mqttPortField, TextField mqttUsernameField,
	        PasswordField mqttPasswordField) {
		if (mqttServerField.getValue() != null) {
			MQTTConfig.getCurrent().setMqttServer(mqttServerField.getValue());
		}
		if (mqttPortField.getValue() != null) {
			MQTTConfig.getCurrent().setMqttPort(mqttPortField.getValue());
		}
		if (mqttUsernameField.getValue() != null) {
			MQTTConfig.getCurrent().setMqttUsername(mqttUsernameField.getValue());
		}
		if (mqttPasswordField.getValue() != null) {
			MQTTConfig.getCurrent().setMqttPassword(mqttPasswordField.getValue());
		}
	}

	// Update the updatePlatforms method for proper platform selection and handling
	synchronized private void updatePlatforms() {
		logger.info("Updating platform selection");
		platformDiv.removeAll();
		platformField = new ComboBox<String>();
		platformField.setWidth("15em");
		platformField.setPlaceholder("Please select a platform");

		// Get platforms either from the current connection or from stored values
		List<String> serverPlatforms = MQTTConfig.getCurrent().getFops();
		if (serverPlatforms == null || serverPlatforms.isEmpty()) {
			// Use stored platforms if available
			serverPlatforms = MQTTConfig.getCurrent().getAvailablePlatforms();
			logger.info("No platforms from server, using stored platforms: {}", serverPlatforms);
		} else {
			logger.info("Using platforms from server: {}", serverPlatforms);
		}

		// Get saved platform
		String savedPlatform = MQTTConfig.getCurrent().getFop();
		logger.info("Previously saved platform: {}", savedPlatform);

		// Set dropdown items
		platformField.setItems(serverPlatforms);

		// Set the dropdown value to match the saved platform if it exists in the server list
		if (savedPlatform != null && !savedPlatform.isEmpty() && serverPlatforms.contains(savedPlatform)) {
			platformField.setValue(savedPlatform);
			logger.info("Set dropdown to saved platform: {}", savedPlatform);

			// Make sure the platform is properly set in the config
			MQTTConfig.getCurrent().setFop(savedPlatform);

		} else if (serverPlatforms.size() == 1) {
			// If only one platform is available, automatically select it
			String singlePlatform = serverPlatforms.get(0);
			platformField.setValue(singlePlatform);
			MQTTConfig.getCurrent().setFop(singlePlatform);
			logger.info("Auto-selected single available platform: {}", singlePlatform);
			MQTTConfig.getCurrent().saveSettings();

		} else {
			// Multiple platforms but no valid saved selection
			platformField.setValue(null);
			MQTTConfig.getCurrent().setFop(null);
			logger.info("Multiple platforms available, user selection required");
		}

		// Set up value change listener for platform selection changes
		platformField.addValueChangeListener(e -> {
			if (e.getValue() != null) {
				String selectedPlatform = e.getValue();
				String previousPlatform = MQTTConfig.getCurrent().getFop();
				boolean platformChanged = previousPlatform == null || !selectedPlatform.equals(previousPlatform);

				logger.info("User selected platform '{}', setting in config", selectedPlatform);
				MQTTConfig.getCurrent().setFop(selectedPlatform);

				// Force the property to be set immediately
				try {
					// Ensure we're saving correctly
					MQTTConfig.getCurrent().saveSettings();
					logger.info("Platform selection saved successfully: {}", selectedPlatform);
				} catch (Exception ex) {
					logger.error("Error saving platform selection: {}", ex.getMessage(), ex);
				}

				// If platform changed, reset all device services and restart
				if (platformChanged) {
					logger.info("Platform changed from '{}' to '{}', stopping all devices and re-initializing",
					        previousPlatform, selectedPlatform);
					stopAllDevices();
					// Start device detection with the new platform
					startDeviceDetection();
				}
			} else if (e.isFromClient()) {
				// Handle clearing the selection
				logger.info("Platform selection cleared by user");
				MQTTConfig.getCurrent().setFop(null);
				MQTTConfig.getCurrent().saveSettings();

				// Stop all devices when platform is cleared
				stopAllDevices();
			}
		});

		platformDiv.add(platformSelectionWarning, platformField);

		// Always update UI status based on connection
		if (MQTTConfig.fullyConnected()) {
			messageConnected();
		} else if (MQTTConfig.getCurrent().connectedNoPlatform()) {
			messageNoPlatform();
		} else {
			messageNotConnected();
		}

		// After platforms are initialized, detect devices
		startDeviceDetection();
	}

	// Ensure proper device detection and auto-start
	private void startDeviceDetection() {
		UI ui = UI.getCurrent();
		if (ui != null) {
			logger.debug("Starting device detection after platform determination");

			try {
				// Show progress indicator
				ui.access(() -> {
					deviceDetectionWait.setVisible(true);
					deviceDetectionProgress.setVisible(true);
					deviceDetectionProgress.setIndeterminate(false);
					deviceDetectionProgress.setValue(0);
				});

				// Submit detection task
				executor.submit(new Runnable() {
					@Override
					public void run() {
						try {
							detectDevices(ui, true);
						} catch (Throwable t) {
							logger.error("Uncaught exception in device detection: {}", t.getMessage(), t);
							ui.access(() -> {
								deviceDetectionWait.setVisible(false);
								errorNotification("Device detection failed: " + t.getMessage());
							});
						}
					}
				});
			} catch (Exception e) {
				logger.error("Failed to submit detection task: {}", e.getMessage(), e);
				ui.access(() -> errorNotification("Failed to start device detection: " + e.getMessage()));
			}
		} else {
			logger.error("Cannot start device detection: UI is null");
		}
	}

	private void stopAllDevices() {
        // Create a safe copy of device configs to avoid concurrent modification
        List<DeviceConfig> devicesToStop = new ArrayList<>(MQTTConfig.getCurrent().getPortToConfig().values());

        // Stop all running services
        for (DeviceConfig deviceConfig : devicesToStop) {
            if (deviceConfig.getFirmataService() != null) {
                logger.info("Stopping device on port {} due to platform change",
                        deviceConfig.getSerialPort());

                // Get the UI components for proper UI updates
                DeviceUIComponents uiComponents = deviceUIComponentsMap.get(deviceConfig.getSerialPort());
                if (uiComponents != null) {
                    UI ui = UI.getCurrent();
                    deviceConfig.getFirmataService().stopDevice(() -> {
                        if (ui != null) {
                            ui.access(() -> confirmStopOk(ui, uiComponents.startButton, uiComponents.stopButton));
                        }
                    });
                } else {
                    // If UI components not found, just stop without UI updates
                    deviceConfig.getFirmataService().stopDevice(null);
                }
                
                // Ensure the device service is fully cleaned up
                deviceConfig.setFirmataService(null);
            }
        }

        // Clear the device configs to force fresh detection
        MQTTConfig.getCurrent().getPortToConfig().clear();
        MQTTConfig.getCurrent().getPortToFirmware().clear();
        
        // Post an update to refresh the UI
        MQTTConfig.getCurrent().getUiEventBus().post(new UIEvent.ConfigsUpdated());
    }

	/**
	 * Auto-start all devices with valid configurations
	 */
	private void autoStartDevices(UI ui) {
		if (!MQTTConfig.fullyConnected()) {
			logger.info("Not fully connected to server with platform, skipping auto-start");
			return;
		}

		logger.info("Auto-starting devices for platform '{}'", MQTTConfig.getCurrent().getFop());
		boolean devicesStarted = false;

		// Get the in-memory list of detected devices - don't load from config
		Map<String, DeviceConfig> deviceConfigs = MQTTConfig.getCurrent().getPortToConfig();
		if (deviceConfigs == null || deviceConfigs.isEmpty()) {
			logger.info("No devices available for auto-start");
			return;
		}

		// Iterate through detected devices
		for (DeviceConfig deviceConfig : deviceConfigs.values()) {
			String deviceType = deviceConfig.getDeviceTypeName();
			if (deviceType != null) {
				// Find the UI components for this device
				DeviceUIComponents uiComponents = deviceUIComponentsMap.get(deviceConfig.getSerialPort());

				// Only proceed if UI components are found
				if (uiComponents != null) {
					Button startButton = uiComponents.startButton;
					Button stopButton = uiComponents.stopButton;

					// Check if a corresponding configuration file exists
					String configPath = ResourceWalker.getLocalDirPath().toString() + "/" + deviceType + ".xlsx";
					boolean configExists = new java.io.File(configPath).exists();

					if (configExists) {
						logger.info("Auto-starting device: {} on port {}", deviceType, deviceConfig.getSerialPort());

						// Stop any existing service
						if (deviceConfig.getFirmataService() != null) {
							deviceConfig.getFirmataService().stopDevice(null);
						}

						// Create service with UI callbacks to update button states
						deviceConfig.setFirmataService(new FirmataService(
						        deviceConfig,
						        () -> confirmStartOk(ui, startButton, stopButton),
						        (ex) -> reportError(ex, ui)));

						try {
							deviceConfig.getFirmataService().startDevice();
							devicesStarted = true;
						} catch (Throwable e1) {
							logger.error("Error auto-starting device {}: {}", deviceType, e1.getMessage());
						}
					} else {
						logger.info("Skipping auto-start for device {} on port {}: configuration file not found",
						        deviceType, deviceConfig.getSerialPort());
					}
				} else {
					logger.info("Skipping auto-start for device {} on port {}: UI components not initialized",
					        deviceType, deviceConfig.getSerialPort());
				}
			}
		}

		if (!devicesStarted) {
			logger.info("No devices were auto-started");
		}
	}

	private void confirmStartOk(UI ui, Button start, Button stop) {
        Objects.requireNonNull(ui).access(() -> {
            start.removeThemeVariants(ButtonVariant.LUMO_PRIMARY);
            stop.addThemeVariants(ButtonVariant.LUMO_PRIMARY);
            stop.setEnabled(true);
            start.setEnabled(false);
        });
    }

    private void confirmStopOk(UI ui, Button start, Button stop) {
        Objects.requireNonNull(ui).access(() -> {
            stop.removeThemeVariants(ButtonVariant.LUMO_PRIMARY);
            start.addThemeVariants(ButtonVariant.LUMO_PRIMARY);
            start.setEnabled(true);
            stop.setEnabled(false);
        });
    }

    private void addFormItemX(Component c, String label) {
        var item = form.addFormItem(c, label);
        item.getElement().getStyle().set("--vaadin-form-item-label-width", "10em");
    }

	private void stopAllDevicesOnDisconnect() {
        // Create a safe copy of device configs to avoid concurrent modification
        List<DeviceConfig> devicesToStop = new ArrayList<>(MQTTConfig.getCurrent().getPortToConfig().values());

        // Stop all running services and update UI
        for (DeviceConfig deviceConfig : devicesToStop) {
            if (deviceConfig.getFirmataService() != null && deviceConfig.getFirmataService().isRunning()) {
                logger.info("Stopping device on port {} due to disconnect",
                        deviceConfig.getSerialPort());

                // Get the UI components for proper UI updates
                DeviceUIComponents uiComponents = deviceUIComponentsMap.get(deviceConfig.getSerialPort());
                if (uiComponents != null) {
                    // Update UI immediately to show stopped state
                    uiComponents.startButton.removeThemeVariants(ButtonVariant.LUMO_PRIMARY);
                    uiComponents.stopButton.removeThemeVariants(ButtonVariant.LUMO_PRIMARY);
                    uiComponents.startButton.setEnabled(false);
                    uiComponents.stopButton.setEnabled(false);
                    
                    // Stop the device service
                    deviceConfig.getFirmataService().stopDevice(() -> {
                        logger.debug("Device stopped on port {}", deviceConfig.getSerialPort());
                    });
                } else {
                    // If UI components not found, just stop without UI updates
                    deviceConfig.getFirmataService().stopDevice(null);
                }
                
                // Ensure the device service is fully cleaned up
                deviceConfig.setFirmataService(null);
            }
        }

        // Clear the device configs completely on disconnect
        MQTTConfig.getCurrent().getPortToConfig().clear();
        MQTTConfig.getCurrent().getPortToFirmware().clear();

        // Post an update to refresh the UI
        MQTTConfig.getCurrent().getUiEventBus().post(new UIEvent.ConfigsUpdated());
    }
}