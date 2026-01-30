##### Release Log

- 2.6.0
 - This version is installed from the Control Panel version 3.0.4 or more recent
 - There is no longer a separate firmata control panel

- 2.5.1: Connections when username/password were configured on owlcms no longer worked in 2.5.0
  - A wrong password will now interrupt the initial autoconnect correctly and give an opportunity to fix the password
  - Fixes were applied to both the MQTT protocol (port 1883) and websocket connections as the Paho library reports errors in different ways.

- 2.5.0: If the port specified ends with 443, the connection will be done using `wss:` to the /mqtt entry point. This is to support the use of MQTT devices on a cloud server.  If the port starts with 8 the connection will be using `ws:`.  Other ports, like the usual 1883 will use `mqtt`

Since 2.3.0

- 2.4.0: Removed unneeded connections established at start-up.  This version should be used for clarity when using owlcms version 61 Connected MQTT devices status reporting.
- 2.3.3: Show the full path to the device definition files.
- 2.3.2: Fix subscription issues when Disconnecting and reconnecting to a server with different platform names
- 2.3.1: Reset the platform list when doing a Disconnect/Connect
- 2.3.0: Automatic connections
  - on startup, automatic connection attempted to the last working configuration
  - if that does not work, scan of the local area network to locate an MQTT server
  - manual choice remains possible
  - once a platform is selected, automatic connection to the detected devices

##### Known issues

- If several browsers are run at the same time display between browsers will not be synchronized. Normally only one at a time is needed anyway.

