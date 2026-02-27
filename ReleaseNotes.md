<!-- markdownlint-disable -->

⚠️⚠️⚠️
**To install and run owlcms-firmata, you need to use the OWLCMS Control Panel.** This location now only the release notes and the software modules that the control panel will install for you.

- **The OWLCMS Control Panel can be downloaded at [this location](https://github.com/owlcms/owlcms-controlpanel/releases). and you can refer to the [Installation Instructions](https://owlcms.github.io/owlcms4-prerelease/#/LocalDownloads.md)**
- **User Documentation for the Control Panel is located at [this location](https://owlcms.github.io/owlcms4-prerelease/#/LocalControlPanel.md)**

<br>
##### Release Notes

- 2.6.1
  - This version fixes the missing release notes

- 2.6.0:
  - This version is installed from the Control Panel version 3.0.4 or more recent
  - There is no longer a separate firmata control panel

- 2.5.1: Connections when username/password were configured on owlcms no longer worked in 2.5.0
  - A wrong password will now interrupt the initial autoconnect correctly and give an opportunity to fix the password
  - Fixes were applied to both the MQTT protocol (port 1883) and websocket connections as the Paho library reports errors in different ways.

- 2.5.0: Support for websockets
  - If the port specified ends with 443, the connection will be done using `wss:` to the /mqtt entry point. This is to support the use of MQTT devices on a cloud server.  For example, `testingMqtt.fly.dev` with port `443` will connect to `wss://testingMqtt.fly.dev:443/mqtt`
  - If the port starts with 8 the connection will be using `ws:` instead of `wss:`.  For local tests you can use localhost with port 8080 (or the normal HTTP port of OWLCMS you use).  OWLCMS will be called on the detected machine as `ws://{detected}:8080/mqtt`.
  - Otherwise, `mqtt` is used (the normal 1883 uses the mqtt protocol)


##### Known issues

- If several browsers are run at the same time display between browsers will not be synchronized. Normally only one at a time is needed anyway.

