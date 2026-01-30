## Instructions for packaging

> **Note:** As of version 2.6+, owlcms-firmata is distributed as a jar file only. The OWLCMS Control Panel (version 3.0+) handles Java runtime management automatically.

### Prerequisites

1. Checkout the repositories as peers (side-by-side in the same parent directory):
   ```bash
   cd ~/git  # or your preferred location
   git clone https://github.com/owlcms/owlcms-firmata.git
   git clone https://github.com/jflamy/firmata4j.git
   cd firmata4j
   git checkout webserial
   ```
   The directory structure should be:
   ```
   parent-directory/
     owlcms-firmata/
     firmata4j/         # on webserial branch
   ```
2. Make sure you have installed Maven (mvn) and that it is on the PATH
3. Install the GitHub CLI (`gh`) for creating releases

### Release Process

1. Edit the `release.sh` script:
   - Update the VERSION NUMBER on line 8 (`TAG=x.y.z`)
   - Set `ALPHA_BETA_RELEASE` on line 11 if needed (e.g., `-rc01` or empty for final)

2. Update the `ReleaseNotes.md` file with changes for this version

3. If device configuration files in `diagrams/` have changed, run:
   ```bash
   cd dist && ./syncXLSX.sh
   ```

4. Open a shell in the repository root and run `./release.sh`
   - This will compile the firmata4j library
   - This will compile an "uberjar" archive containing all dependencies
   - This will create a GitHub release with the jar file

### Development

For local development, device configuration files should be in `src/main/resources/devices/`. 
These are bundled into the jar and auto-extracted to the user's config directory on first run.

To sync config files from `diagrams/` to resources:
```bash
cd dist && ./syncXLSX.sh
```
