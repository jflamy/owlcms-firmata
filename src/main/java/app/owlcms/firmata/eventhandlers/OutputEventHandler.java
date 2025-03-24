package app.owlcms.firmata.eventhandlers;

import java.util.List;

import org.firmata4j.Pin;
import org.slf4j.LoggerFactory;

import app.owlcms.firmata.refdevice.OutputPinDefinition;
import app.owlcms.firmata.refdevice.RefDevice;
import app.owlcms.firmata.refdevice.RefDevice.CycleDoer;
import app.owlcms.firmata.refdevice.RefDevice.FlashDoer;
import app.owlcms.firmata.refdevice.RefDevice.ToneDoer;
import app.owlcms.firmata.utils.LoggerUtils;
import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;

/**
 * Perform actions resulting from the receipt of an MQTT Message.
 * 
 * These actions affect the output components (LEDs, buzzers, relays, etc.) The actions to be performed are found in a definition table.
 * 
 * @author jflamy
 *
 */
public class OutputEventHandler {
	private List<OutputPinDefinition> definitions;
	private final Logger logger = (Logger) LoggerFactory.getLogger(OutputEventHandler.class);

	public OutputEventHandler(List<OutputPinDefinition> definitions) {
		this.setDefinitions(definitions);
		logger.setLevel(Level.DEBUG);
	}

	public List<OutputPinDefinition> getDefinitions() {
		return definitions;
	}

	public void handle(String topic, String messageStr, RefDevice board) {
		getDefinitions().stream().filter(d1 -> matchTopic(topic, d1)
		        && (d1.message == null || d1.message.isBlank() || d1.message.trim().contentEquals(messageStr.trim())))
		        //.peek(d1 -> logger.debug("======== {} {}", d1.topic, topic))
		        .forEach(d -> {
			        doPin(d, board);
		        });
	}

	public boolean matchTopic(String topic, OutputPinDefinition d1) {
		boolean exactMatch = d1.topic.equals(topic);
		return exactMatch;
	}

	public void setDefinitions(List<OutputPinDefinition> definitions) {
		this.definitions = definitions;
	}

	private void doPin(OutputPinDefinition d, RefDevice board) {
		logger.debug("pin {} {} {} -> {} {} {}", d.getPinNumber(), d.topic, d.message, d.description, d.action,
		        d.parameters);
		Pin pin = board.getPin(d.getPinNumber());
		new Thread(() -> {
			try {
				switch (d.action.toUpperCase()) {
					case "OFF" -> {
						board.pinSetValue(pin, 0L);
					}
					case "ON" -> {
						if (d.parameters != null && d.parameters.isBlank()) {
							board.pinSetValue(pin, 1L);
						} else {
							FlashDoer doer = board.doFlash(pin, d.parameters, "ON");
							board.pinSetValue(pin, 0L);
							board.cleanInterruptibles(doer);
						}
					}
					case "FLASH" -> {
						FlashDoer doer = board.doFlash(pin, d.parameters, "FLASH");
						board.pinSetValue(pin, 0L);
						board.cleanInterruptibles(doer);
					}
					case "TONE" -> {
						ToneDoer doer = board.doTones(pin, d.parameters);
						board.pinSetValue(pin, 0L);
						board.cleanInterruptibles(doer);
					}
					case "CYCLE" -> {
						CycleDoer doer = board.doCycle(pin, d.parameters);
						board.pinSetValue(pin, 0L);
						board.cleanInterruptibles(doer);
					}
				}
			} catch (Exception e) {
				logger.error("Exception {}", LoggerUtils.stackTrace(e));
			}
		}).start();
	}

}
