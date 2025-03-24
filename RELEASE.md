##### Changes since 2.0.0

- 2.2.0: pin outputs with "ON" but no duration were not working. (These are needed for full jury panel)
- 2.1.1: Do not overwrite files on startup, to work with firmata-controlpanel 0.9.1 simplified updating.
- 2.1.0: fix to work with version 55.3
- 2.0.8: work with a control panel similar to that of owlcms
  - the control panel will pass additional options to tell where the configuration files are and what port to use.


- 2.0.6: fixed the configuration selection to work on Linux and Raspberry Pi.
- 2.0.5: clientId now less than 23 characters to prevent spurious error message in owlcms
- host/port/username from last connection are saved and restored on next start
- The Windows setup was not copying the default configuration files to the owlcms/devices directory in the user HOMEDIR.
- The detection of the remote platforms has been made more robust.
- The default port is now 8090 to avoid conflicts with owlcms

##### Release 2.0

- Complete rework of the user interface
  - See the [INSTRUCTIONS.md](https://github.com/jflamy/owlcms-firmata/blob/v24/INSTRUCTIONS.md) file in the repository

- A single instance of the program can handle multiple connected devices
- Detection of the connected devices.
- The name of the firmware is used to locate the matching configuration file by default.

##### Known issues

- If several browsers are run at the same time display between browsers will not be synchronized. Normally only one at a time is needed anyway.

