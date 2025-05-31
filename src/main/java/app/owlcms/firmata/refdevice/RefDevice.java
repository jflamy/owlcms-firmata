package app.owlcms.firmata.refdevice;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Timer;
import java.util.TimerTask;
import java.util.stream.Collectors;

import org.firmata4j.IODevice;
import org.firmata4j.Pin;
import org.firmata4j.Pin.Mode;
import org.slf4j.LoggerFactory;

import app.owlcms.firmata.eventhandlers.InputEventHandler;
import app.owlcms.firmata.eventhandlers.OutputEventHandler;
import app.owlcms.firmata.primitives.Note;
import app.owlcms.firmata.primitives.Tone;
import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;

public class RefDevice {

	// https://github.com/arduino/ArduinoCore-avr/blob/master/variants/mega/pins_arduino.h
	public static final int NB_MEGA_PINS = 69;

	private final Logger logger = (Logger) LoggerFactory.getLogger(RefDevice.class);
	private IODevice firmataDevice;
	private InputEventHandler inputEventHandler;
	private OutputEventHandler outputEventHandler;
	private String serialPortName;

	private volatile boolean initialized = false;
	private final Object initLock = new Object();

	public RefDevice(String myPort, IODevice device, OutputEventHandler outputEventHandler,
	        InputEventHandler inputEventHandler) {
		logger.setLevel(Level.DEBUG);
		this.firmataDevice = device;
		this.serialPortName = myPort;
		this.outputEventHandler = outputEventHandler;
		this.inputEventHandler = inputEventHandler;
	}

	public void init() throws Exception {
		try {
			initBoard();
			initModes();
			if (logger.isTraceEnabled()) {
				showPinConfig();
			}
		} catch (Exception ex) {
			logger.debug("board init exception {}", ex.getMessage());
			logger.debug("before throwable board init exception");
			Throwable cause = ex.getCause() != null ? ex.getCause() : ex;
			logger.debug("after throwable board init exception", ex.getMessage());
			throw new RuntimeException(cause);
		}

	}

	public void initBoard() throws Exception {
		// Check if already initialized
		synchronized(initLock) {
			if (initialized) {
				logger.debug("Board already initialized for port {}", serialPortName);
				return;
			}
			
			try {
				firmataDevice.start(); // start comms with board;
				logger.info("Communication started on port {}", serialPortName);
				firmataDevice.ensureInitializationIsDone();
				logger.info("Board initialized.");
				
				// Set initialized flag after successful init
				initialized = true;
			} catch (Exception ex) {
				logger.error("Could not connect to board. " + ex);
				throw new RuntimeException(ex.getCause() != null ? ex.getCause() : ex);
			}
		}
	}

	public void initModes() {
		outputEventHandler.getDefinitions().stream().forEach(i -> {
			try {
				logger.trace("output {}", i.getPinNumber());
				Pin pin = firmataDevice.getPin(i.getPinNumber());
				pin.setMode(Mode.OUTPUT);
				pin.setValue(0L);
			} catch (Exception e) {
				logger.trace("exception setting outputs {} {}", i.getPinNumber(), e);
			}
		});
		inputEventHandler.getDefinitions().stream().forEach(i -> {
			try {
				logger.trace("button {}", i.getPinNumber());
				firmataDevice.getPin(i.getPinNumber()).setMode(Mode.PULLUP);
			} catch (Exception e) {
				logger.trace("exception setting outputs {} {}", i.getPinNumber(), e);
			}
		});
	}

	public void showPinConfig() throws IOException {
		for (int i = 0; i < firmataDevice.getPinsCount(); i++) {
			Pin pin = firmataDevice.getPin(i);
			logger.debug("{} {}", i, pin.getMode());
		}
	}

	public void startupLED() {
		Pin myLED = firmataDevice.getPin(13);
		new Timer().schedule(new TimerTask() {
			@Override
			public void run() {
				try {
					logger.debug("startup LED on");
					myLED.setValue(1);
				} catch (IllegalStateException | IOException e) {
				}
			}
		}, 2500);
		new Timer().schedule(new TimerTask() {
			@Override
			public void run() {
				try {
					logger.debug("startup LED off");
					myLED.setValue(0);
				} catch (IllegalStateException | IOException e) {
				}
			}
		}, 5000);
	}

	public Pin getPin(int pinNumber) {
		return firmataDevice.getPin(pinNumber);
	}

	public class FlashDoer implements Interruptible {
		private String whereFrom;

		public FlashDoer(String string) {
			this.whereFrom = string;
		}

		@Override
		public boolean isInterrupted() {
			return interrupted;
		}

		private boolean interrupted;

		@Override
		public void setInterrupted(boolean interrupted) {
			this.interrupted = interrupted;
		}

		public void doit(long totalDuration, long onDuration, long offDuration, Pin pin, RefDevice board) {
			long start = System.currentTimeMillis();
			while (!interrupted && (System.currentTimeMillis() - start) < totalDuration) {
				try {
					long targetDuration;
					board.pinSetValue(pin,1L);

					targetDuration = System.currentTimeMillis() + onDuration;
					while (!interrupted && (System.currentTimeMillis() < targetDuration)) {
						Thread.yield();
					}

					board.pinSetValue(pin,0L);
					targetDuration = System.currentTimeMillis() + offDuration;
					while (!interrupted && (System.currentTimeMillis() < targetDuration)) {
						Thread.yield();
					}
				} catch (IllegalStateException e) {
					;
				}
			}
		}

		@Override
		public String getWhereFrom() {
			return whereFrom;
		}
	}

	public FlashDoer doFlash(Pin pin, String parameters, String action) {
		String[] topLevelParams = parameters.split("[-]");
		String[] params = topLevelParams[0].split("[ ,;]");
		int totalDuration;
		int onDuration;
		int offDuration;

		try {
			totalDuration = Integer.parseInt(params[0].trim());
			if (params.length > 1) {
				onDuration = Integer.parseInt(params[1].trim());
				offDuration = Integer.parseInt(params[2].trim());
			} else {
				onDuration = totalDuration + 1;
				offDuration = 0;
			}
		} catch (NumberFormatException | IllegalStateException e1) {
			return null;
		}

		List<Integer> interruptionButtonInts = readInterruptionButtons(pin, topLevelParams, 1);
		FlashDoer th = new FlashDoer(action + " " + pin.getIndex());
		addInterruptible(interruptionButtonInts, th);
		th.doit(totalDuration, onDuration, offDuration, pin, this);
		return th;
	}

	private synchronized void addInterruptible(List<Integer> interruptionButtonInts, Interruptible th) {
		if (interruptionButtonInts == null || interruptionButtonInts.size() == 0) {
			return;
		}
		logger.debug("adding interruption buttons for {}: {}", th.getWhereFrom(), interruptionButtonInts);
		for (int i : interruptionButtonInts) {
			// pressing button i should interrupt the thread (red and white buttons)
			addInterruptible((byte) i, th);
		}
	}

	private List<Integer> readInterruptionButtons(Pin pin, String[] params, int paramIndex) {
		List<Integer> interruptionButtonInts = new ArrayList<>();
		if (params.length > paramIndex) {
			String[] interruptionButtons = params[paramIndex].trim().split("[,;]");
			for (String buttonNo : interruptionButtons) {
				int buttonIndex = Integer.parseInt(buttonNo);
				interruptionButtonInts.add(buttonIndex);
			}
			//logger.debug("pin {} can be interrupted by {}", pin.getIndex(), interruptionButtonInts);
		}
		return interruptionButtonInts;
	}

	Map<Byte, List<Interruptible>> toBeInterrupted = new HashMap<>();

	public synchronized void addInterruptible(byte interruptionButtonIndex, app.owlcms.firmata.refdevice.Interruptible th) {
		List<Interruptible> threads = toBeInterrupted.get(interruptionButtonIndex);
		if (threads == null) {
			threads = new ArrayList<>();
		}
		threads.add(th);
		synchronized (toBeInterrupted) {
			toBeInterrupted.put(interruptionButtonIndex, threads);
		}
	}

	public synchronized void interruptInterruptibles(byte interruptionButtonIndex) {
		List<app.owlcms.firmata.refdevice.Interruptible> interruptibles = null;
		synchronized (toBeInterrupted) {
			interruptibles = toBeInterrupted.get(interruptionButtonIndex);
			if (interruptibles != null) {
				if (logger.isEnabledFor(Level.DEBUG)) {
					logger.debug("button {} : interrupting {}", 
							interruptionButtonIndex,
							interruptibles.stream().map(i -> i.getWhereFrom()).collect(Collectors.joining(","))
							);
				}
				for (var interruptible : interruptibles) {
					interruptible.setInterrupted(true);
				}
				List<Byte> emptyButtons = new ArrayList<>();

				// each thread can be registered to multiple buttons. remove from all buttons.
				for (Entry<Byte, List<Interruptible>> entry : toBeInterrupted.entrySet()) {
					var buttonInterruptibles = entry.getValue();
					buttonInterruptibles.removeAll(interruptibles);
					if (buttonInterruptibles.isEmpty()) {
						emptyButtons.add(entry.getKey());
					}
				}
				// clean-up
				for (Byte b : emptyButtons) {
					logger.debug("no more entries for button {}", b);
					toBeInterrupted.remove(b);
				}
			}
		}
	}

	public synchronized void cleanInterruptibles(Interruptible interruptible) {
		if (interruptible != null) {
			synchronized (toBeInterrupted) {
				logger.debug("interruptible {} done", interruptible.getWhereFrom());
				// each thread can be registered to multiple buttons. remove from all buttons.
				List<Byte> emptyButtons = new ArrayList<>();
				for (Entry<Byte, List<Interruptible>> entry : toBeInterrupted.entrySet()) {
					var buttonInterruptibles = entry.getValue();
					buttonInterruptibles.remove(interruptible);
					if (buttonInterruptibles.isEmpty()) {
						emptyButtons.add(entry.getKey());
					}
				}
				// clean-up
				for (Byte b : emptyButtons) {
					logger.debug("no more entries for button {}", b);
					toBeInterrupted.remove(b);
				}
			}
		}
	}

	public class ToneDoer implements Interruptible {
		private boolean interrupted;
		private String whereFrom;

		public ToneDoer(String string) {
			this.whereFrom = string;
		}

		@Override
		public boolean isInterrupted() {
			return interrupted;
		}

		@Override
		public void setInterrupted(boolean interrupted) {
			this.interrupted = interrupted;
		}

		public void doit(String[] params, Pin pin, RefDevice board) {
			try {
				interrupted: for (int i = 0; i < params.length; i = i + 2) {
					try {
						if (interrupted) {
							break interrupted;
						}
						var curNote = Note.valueOf(params[i].trim());
						var curDuration = Integer.parseInt(params[i + 1].trim());
						logger.debug("*** {} {}",curNote,curDuration);
						Tone tone = new Tone(curNote.getFrequency(), curDuration, pin, board);
						tone.playWait(this);
					} catch (InterruptedException e) {
						break interrupted;
					} catch (IllegalArgumentException e1) {
						logger.error("pin {} illegal TONE pair, expecting Note,Duration: {},{}", pin.getIndex(),
						        params[i], params[i + 1]);
					}
				}
			} catch (ArrayIndexOutOfBoundsException e2) {
				logger./**/warn("pin {} illegal TONE array length, must be > 0 and multiple of 2", pin.getIndex());
			}
		}

		@Override
		public String getWhereFrom() {
			return whereFrom;
		}
	}
	
	public class CycleDoer implements Interruptible {
		private boolean interrupted;
		private String whereFrom;

		public CycleDoer(String string) {
			this.whereFrom = string;
		}

		@Override
		public boolean isInterrupted() {
			return interrupted;
		}

		@Override
		public void setInterrupted(boolean interrupted) {
			this.interrupted = interrupted;
		}

		public void doit(String[] params, Pin pin, RefDevice board) {
			try {
				long onDuration = Integer.valueOf(params[0]);
				long cycleDuration = Integer.valueOf(params[1]);
				Integer pins[] = new Integer[params.length - 2];
				for (int i = 2; i < params.length; i = i + 1) {
					pins[i-2] = Integer.valueOf(params[i]);
				}
				interrupted: for (int i = 0; i < pins.length; i = i + 1) {				
					try {
						if (interrupted) {
							break interrupted;
						}				
						long start = System.currentTimeMillis();
						while (!interrupted 
								&& (System.currentTimeMillis() - start ) < cycleDuration
								) {
							try {
								long targetDuration;
								board.pinSetValue(board.getPin(pins[i]),1L);
								targetDuration = System.currentTimeMillis() + onDuration;
								while (!interrupted && (System.currentTimeMillis() < targetDuration)) {
									Thread.yield();
								}
								board.pinSetValue(pin,0L);
							} catch (IllegalStateException e) {
								;
							}
						}
					} catch (IllegalArgumentException e1) {
						logger.error("pin {} illegal CYCLE spec pair, expecting Note,Duration: {},{}", pin.getIndex(),
						        params[i], params[i + 1]);
					}
				}
			} catch (ArrayIndexOutOfBoundsException e2) {
				logger./**/warn("pin {} illegal CYCLE array length, must be 5", pin.getIndex());
			}
		}

		@Override
		public String getWhereFrom() {
			return whereFrom;
		}
	}

	public ToneDoer doTones(Pin pin, String parameters) {
		String[] topLevelParams = parameters.split("[-]");
		String[] params = topLevelParams[0].trim().split("[,;]");
		
		List<Integer> interruptionButtonInts = readInterruptionButtons(pin, topLevelParams, 1);
		ToneDoer th = new ToneDoer("Tone " + pin.getIndex());
		addInterruptible(interruptionButtonInts, th);
		th.doit(params, pin, this);
		return th;
	}
	
	public CycleDoer doCycle(Pin pin, String parameters) {
		String[] topLevelParams = parameters.split("[-]");
		String[] params = topLevelParams[0].trim().split("[,;]");
		
		List<Integer> interruptionButtonInts = readInterruptionButtons(pin, topLevelParams, 1);
		CycleDoer th = new CycleDoer("Cycle " + pin.getIndex());
		addInterruptible(interruptionButtonInts, th);
		th.doit(params, pin, this);
		return th;
	}

	public void stop() {
		try {
			logger.info("stopping device {} ", firmataDevice);
			firmataDevice.stop();
		} catch (IOException e) {
			throw new RuntimeException(e);
		}
	}

	synchronized public void pinSetValue(Pin pin, long l) {
		try {
			pin.setValue(l);
		} catch (IllegalStateException | IOException e) {
			logger.debug("cannot set pin {} value : {}", pin.getIndex(), e);
		}
	}
}
