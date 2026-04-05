# Target Installer (standalone)

This is a vendored copy of the KNIME target installer (PDE/OSGi) with a
standalone launcher build script. It is intentionally not a Gradle
module; build it via the script below.

## Build launcher

```bash
chmod +x tools/target-installer/scripts/build-launcher.sh
# preferred: prebuilt runtime archive (no local Eclipse SDK needed)
RUNTIME_ZIP=/path/to/eclipse-runtime.zip \
tools/target-installer/scripts/build-launcher.sh

# fallback: build runtime from an Eclipse SDK
ECLIPSE_SDK=~/eclipse \
P2_REPOSITORIES="https://download.eclipse.org/releases/2024-12" \
tools/target-installer/scripts/build-launcher.sh
```

Output:
- `tools/target-installer/dist/target-installer-launcher.jar`

## Run (self-contained install)

```bash
java -jar tools/target-installer/dist/target-installer-launcher.jar --cache=persistent -- \
  -profileId DummyProfile \
  -targetDefinition /path/to/KNIME-AP.target \
  -install-folder /path/to/output/install
```

Defaults:
- If `-install-folder` is provided and `-bundle-pool`/`-p2Path` are
  omitted, the launcher uses:
  - `-bundle-pool <install-folder>/bundle-pool`
  - `-p2Path <install-folder>/p2`
