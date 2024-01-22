package app.owlcms.firmata.ui;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Map.Entry;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentSkipListMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.eclipse.paho.client.mqttv3.MqttException;
import org.firmata4j.firmata.FirmataDevice;
import org.firmata4j.transport.JSerialCommTransport;
import org.slf4j.LoggerFactory;

import com.fazecast.jSerialComm.SerialPort;
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
import com.vaadin.flow.component.icon.Icon;
import com.vaadin.flow.component.notification.Notification;
import com.vaadin.flow.component.notification.Notification.Position;
import com.vaadin.flow.component.notification.NotificationVariant;
import com.vaadin.flow.component.orderedlayout.HorizontalLayout;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.component.progressbar.ProgressBar;
import com.vaadin.flow.component.textfield.PasswordField;
import com.vaadin.flow.component.textfield.TextField;
import com.vaadin.flow.component.upload.Upload;
import com.vaadin.flow.component.upload.UploadI18N;
import com.vaadin.flow.component.upload.UploadI18N.AddFiles;
import com.vaadin.flow.component.upload.UploadI18N.Uploading;
import com.vaadin.flow.router.Route;

import app.owlcms.firmata.config.Config;
import app.owlcms.firmata.mqtt.ConfigMQTTMonitor;
import app.owlcms.firmata.refdevice.DeviceConfig;
import app.owlcms.firmata.utils.LoggerUtils;
import app.owlcms.utils.Resource;
import app.owlcms.utils.ResourceWalker;
import ch.qos.logback.classic.Logger;

/**
 * The main view contains a button and a click listener.
 */
@Route("")
public class MainView extends VerticalLayout {
	private static ConfigMQTTMonitor mqttMonitor;
	private static final String SECTION_MARGIN_TOP = "0em";
	FormLayout form = new FormLayout();
	int i; // we count getting the serial ports as step 1.
	private ArrayList<String> availableConfigFiles;
	private ComboBox<Resource> configSelect;
	private ProgressBar deviceDetectionProgress;
	private HorizontalLayout deviceDetectionWait;
	private Html deviceSelectionExplanation;
	private ExecutorService executor = Executors.newSingleThreadExecutor();
	private Paragraph fullyConnectedWarning;
	private Logger logger = (Logger) LoggerFactory.getLogger(MainView.class);
	private ComboBox<String> platformField;

	private Div portsDiv;

	private TreeMap<String, DeviceConfig> portToConfig = new TreeMap<>();

	private Map<String, String> portToFirmare;

	public MainView() {
		this.setMargin(true);
		this.setPadding(true);
		this.getStyle().set("margin", "1em");
		UI ui = UI.getCurrent();
		getPlatformsFromServer(ui);
		setWidth("80%");
		this.getElement().getStyle().set("margin-left", "1em");
		form.setResponsiveSteps(new ResponsiveStep("0px", 1, LabelsPosition.ASIDE));
		this.getElement().getStyle().set("margin-bottom", "1em");

		var title = new H2("owlcms Refereeing Device Control");
		title.getStyle().set("margin-top", "0.5em");
		add(title);


		var deviceSelectionTitle = new H3("Devices");
		deviceSelectionTitle.getStyle().set("margin-top", SECTION_MARGIN_TOP);
		
		fullyConnectedWarning = new Paragraph("You cannot start devices until you have connected to the server and selected a platform.");
		fullyConnectedWarning.setVisible(false);
		deviceSelectionExplanation = new Html("""
		        <div>Detecting connected devices. <b>Please wait.</b></div>
		        """);
		deviceSelectionExplanation.getStyle().set("width", "40em");
		deviceDetectionProgress = new ProgressBar();
		deviceDetectionWait = new HorizontalLayout(deviceSelectionExplanation, deviceDetectionProgress);
		deviceDetectionWait.setWidth("40em");

		portsDiv = new Div();
		portsDiv.add(deviceSelectionTitle, deviceDetectionWait, fullyConnectedWarning);



		showServerConfig();
		
		showPlafromSelection();
		
		getAvailableConfigNames();
		executor.submit(() -> {
			List<SerialPort> serialPorts = getSerialPorts();
			ui.access(() -> deviceDetectionProgress.setValue(1.0 / (serialPorts.size() + 1)));
			portToFirmare = buildPortMap(serialPorts, ui);
			for (Entry<String, String> pf : portToFirmare.entrySet()) {
				portToConfig.put(pf.getKey(), new DeviceConfig(pf.getKey(), pf.getValue()));
			}
			showDeviceConfigs(ui);
		});
		
		add(createSeparator(), portsDiv);
	}

	public void doPlatformsUpdate(UI ui) {
		logger.warn("platforms ***** {}",Config.getCurrent().getFops());
		ui.access(() -> {
			platformField.setItems(Config.getCurrent().getFops());
		});
	}

	@Override
	protected void onAttach(AttachEvent e) {
	}

	private void addFormItemX(Component c, String string) {
		var item = form.addFormItem(c, string);
		item.getElement().getStyle().set("--vaadin-form-item-label-width", "10em");
	}

	private Map<String, String> buildPortMap(List<SerialPort> serialPorts, UI ui) {
		Map<String, String> portToFirmware = new ConcurrentSkipListMap<>();
		i = 1;
		for (SerialPort sp : serialPorts) {
			FirmataDevice device = null;
			try {
				String systemPortName = sp.getSystemPortName();
				ui.access(() -> deviceDetectionProgress
				        .setValue(((float) i++) / (serialPorts.size() + 1)));
				device = new FirmataDevice(new JSerialCommTransport(systemPortName));
				device.ensureInitializationIsDone();
				String firmware = device.getFirmware();
				firmware = firmware.replace(".ino", "");
				portToFirmware.put(systemPortName, firmware);
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
		return portToFirmware;
	}
	private void confirmStartOk(UI ui, Button start, Button stop) {
		ui.access(() -> {
			// Notification.show("Device started", 2000, Position.MIDDLE);
			start.removeThemeVariants(ButtonVariant.LUMO_PRIMARY);
			stop.addThemeVariants(ButtonVariant.LUMO_PRIMARY);
		});
	}
	private void confirmStopOk(UI ui, Button start, Button stop) {
		ui.access(() -> {
			// Notification.show("Device stopped", 2000, Position.MIDDLE);
			stop.removeThemeVariants(ButtonVariant.LUMO_PRIMARY);
			start.addThemeVariants(ButtonVariant.LUMO_PRIMARY);
		});
	}

	@SuppressWarnings("unused")
	private void createSerialCombo(DeviceConfig deviceConfig) {
		ComboBox<SerialPort> serialCombo = new ComboBox<>();
		serialCombo.setPlaceholder("Select Port");

		List<SerialPort> serialPorts = getSerialPorts();
		serialCombo.setItems(serialPorts);
		serialCombo.setItemLabelGenerator((i) -> i.getSystemPortName());
		serialCombo.setValue(serialPorts.size() > 0 ? serialPorts.get(0) : null);
		serialCombo.addThemeName("bordered");
		serialCombo.addValueChangeListener(e -> deviceConfig.setSerialPort(e.getValue().getSystemPortName()));
	}

	@SuppressWarnings("unused")
	private void createUploadButton(DeviceConfig deviceConfig, Upload upload) {
		UploadI18N i18n = new UploadI18N();
		i18n.setUploading(
		        new Uploading().setError(new Uploading.Error().setUnexpectedServerError("File could not be loaded")))
		        .setAddFiles(new AddFiles().setOne("Upload Device Configuration"));
		upload.setDropLabel(new Span("Configuration files are copied to the installation directory"));
		upload.setI18n(i18n);
		upload.addStartedListener(event -> {
			logger.error("started {}" + event.getFileName());
		});
		upload.addSucceededListener(e -> {
			upload.clearFileList();
			deviceConfig.setDeviceTypeName(e.getFileName());
		});
		upload.addFailedListener(e -> {
			logger.error("failed upload {}", e.getReason());
			ConfirmDialog dialog = new ConfirmDialog();
			dialog.setHeader("Upload Failed");
			dialog.setText(new Html("<p>" + e.getReason().getLocalizedMessage() + "</p>"));
			dialog.setConfirmText("OK");
			dialog.open();
			upload.clearFileList();
		});
		upload.addFileRejectedListener(event -> {
			logger.error("rejected {}" + event.getErrorMessage());
		});
	}

	private void errorNotification(String errorMessage) {
		Notification notification = new Notification();
		notification.addThemeVariants(NotificationVariant.LUMO_ERROR);
		notification.setPosition(Position.MIDDLE);

		Div text = new Div(new Text(errorMessage));

		Button closeButton = new Button(new Icon("lumo", "cross"));
		closeButton.addThemeVariants(ButtonVariant.LUMO_TERTIARY_INLINE);
		closeButton.setAriaLabel("Close");
		closeButton.addClickListener(event -> {
			notification.close();
		});

		HorizontalLayout layout = new HorizontalLayout(text, closeButton);
		layout.setAlignItems(Alignment.CENTER);

		notification.add(layout);
		notification.open();
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

	private void getPlatformsFromServer(UI ui) {
		if (mqttMonitor == null) {
			new Thread(() -> {
				mqttMonitor = new ConfigMQTTMonitor(this, ui);
				try {
					mqttMonitor.publishMqttMessage("owlcms/config", "");
				} catch (MqttException e) {
					logger.error("server config request error {}", e);
				}
			}).start();
		}
	}

	private List<SerialPort> getSerialPorts() {
		SerialPort[] ports = SerialPort.getCommPorts();
		return Arrays.asList(ports);

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

	private void reportError(Throwable ex, UI ui) {
		logger.error("could not start {}", ex.toString());
		ui.access(() -> {
			ConfirmDialog dialog = new ConfirmDialog();
			dialog.setHeader("Device Initialization Failed");
			dialog.setText(new Html("<p>" + ex.getCause().getMessage().toString() + "</p>"));
			dialog.setConfirmText("OK");
			dialog.open();
		});
	}

	private void showDeviceConfig(DeviceConfig deviceConfig, String platform) {
		HorizontalLayout dcl = new HorizontalLayout();

		Button stop = new Button("Stop Device");
		Button start = new Button("Start Device");
		
		configSelect = new ComboBox<Resource>();
		configSelect.setPlaceholder("No configuration selected");
		configSelect.setHelperText("Select a configuration");
		String string = ResourceWalker.getLocalDirPath().toString();
		logger.warn("menu items from directory {}", string);
		List<Resource> resourceList = new ResourceWalker().getResourceList(string,
		        ResourceWalker::relativeName, null, Locale.getDefault(), true);
		configSelect.setItems(resourceList);
		Resource curResource = resourceList.stream()
		        .filter(r -> r.getFileName().contentEquals(deviceConfig.getDeviceTypeName() + ".xlsx")).findFirst()
		        .orElse(null);
		configSelect.setValue(curResource);
		boolean platformAndConnected = Config.fullyConnected();
		if (curResource == null || !platformAndConnected) {
			fullyConnectedWarning.setVisible(true);
			start.setEnabled(false);
			stop.setEnabled(false);
		}
		configSelect.setWidth("15em");
		configSelect.addValueChangeListener(e -> {
			if (!e.isFromClient()) {
				return;
			}
			deviceConfig.setDevice(e.getValue().toString());
			fullyConnectedWarning.setVisible(false);
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

	}

	private void showDeviceConfigs(UI ui) {
		logger.warn("show device configs");
		ui.access(() -> {
				deviceDetectionWait.setVisible(false);
				for (DeviceConfig dc : portToConfig.values()) {
					showDeviceConfig(dc, Config.getCurrent().getFop());
				}
				fullyConnectedWarning.setVisible(!Config.fullyConnected());
		});
	}

	private void showPlafromSelection() {
		add(createSeparator());
		var platformTitle = new H3("Platform");
		platformTitle.getStyle().set("margin-top", SECTION_MARGIN_TOP);
		
		platformField = new ComboBox<String>();
		platformField.setWidth("15em");
		List<String> fops = Config.getCurrent().getFops();
		platformField.setPlaceholder("Please select a platform");
		platformField.setItems(fops);
		if (fops.size() == 1) {
			Config.getCurrent().setFop(fops.get(0));
		}
		platformField.setValue(Config.getCurrent().getFop());
		platformField.addValueChangeListener(e -> Config.getCurrent().setFop(e.getValue()));
		
		this.add(platformTitle, platformField);
	}

	private Hr createSeparator() {
		var hr = new Hr();
		hr.getStyle().set("height", "3px");
		return hr;
	}

	private void showServerConfig() {
		UI ui = UI.getCurrent();
		Button connect = new Button("Connect");
		Button disconnect = new Button("Disconnect");
		connect.addThemeVariants(ButtonVariant.LUMO_PRIMARY);
		connect.addClickListener(e -> {
			getPlatformsFromServer(ui);
			if (Config.getCurrent().isConnected()) {
				connect.removeThemeVariants(ButtonVariant.LUMO_PRIMARY);
				disconnect.addThemeVariants(ButtonVariant.LUMO_PRIMARY);
			}
		});
		disconnect.addClickListener(e -> {
			getPlatformsFromServer(ui);
			if (Config.getCurrent().isConnected()) {
				disconnect.removeThemeVariants(ButtonVariant.LUMO_PRIMARY);
				connect.addThemeVariants(ButtonVariant.LUMO_PRIMARY);
			}
		});
		
		Span buttons = new Span(connect,new Text("  "),disconnect);
		var mqttConfigTitle = new HorizontalLayout(new H3("MQTT Server"),buttons);
		mqttConfigTitle.setSpacing(true);
		mqttConfigTitle.setAlignItems(Alignment.BASELINE);
		mqttConfigTitle.getStyle().set("margin-top", "0.5em");

		TextField mqttServerField = new TextField();
		mqttServerField.setHelperText("This is normally the address of the owlcms server");
		mqttServerField.setValue(Config.getCurrent().getMqttServer());
		mqttServerField.addValueChangeListener(e -> Config.getCurrent().setMqttServer(e.getValue()));

		TextField mqttPortField = new TextField();
		mqttPortField.setValue(Config.getCurrent().getMqttPort());
		mqttPortField.addValueChangeListener(e -> Config.getCurrent().setMqttPort(e.getValue()));

		TextField mqttUsernameField = new TextField();
		mqttUsernameField.setValue(Config.getCurrent().getMqttUsername());
		mqttUsernameField.addValueChangeListener(e -> Config.getCurrent().setMqttUsername(e.getValue()));

		PasswordField mqttPasswordField = new PasswordField();
		mqttPasswordField.setValue(Config.getCurrent().getMqttPassword());
		mqttPasswordField.addValueChangeListener(e -> Config.getCurrent().setMqttPassword(e.getValue()));

		form.setResponsiveSteps(
		        // Use one column by default
		        new ResponsiveStep("0", 1),
		        // Use two columns, if layout's width exceeds 500px
		        new ResponsiveStep("500px", 2));
		this.add(mqttConfigTitle);
		this.setWidth("1000px");
		addFormItemX(mqttServerField, "MQTT Server");
		addFormItemX(mqttPortField, "MQTT Port");
		addFormItemX(mqttUsernameField, "MQTT Username");
		addFormItemX(mqttPasswordField, "MQTT Password");
		this.getStyle().set("margin-top", "0");
		this.setMargin(false);
		this.setPadding(false);
		this.add(form);

		updateServerConfigFromFields(
		        mqttServerField, mqttPortField, mqttUsernameField, mqttPasswordField);
	}

	private void updateServerConfigFromFields(
	        TextField mqttServerField, TextField mqttPortField, TextField mqttUsernameField,
	        PasswordField mqttPasswordField) {
		if (mqttServerField.getValue() != null) {
			Config.getCurrent().setMqttServer(mqttServerField.getValue());
		}
		if (mqttPortField.getValue() != null) {
			Config.getCurrent().setMqttPort(mqttPortField.getValue());
		}
		if (mqttUsernameField.getValue() != null) {
			Config.getCurrent().setMqttUsername(mqttUsernameField.getValue());
		}
		if (mqttPasswordField.getValue() != null) {
			Config.getCurrent().setMqttPassword(mqttPasswordField.getValue());
		}
	}
}
