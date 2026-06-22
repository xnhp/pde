# Target Installer (standalone)

This is a vendored copy of the KNIME target installer (PDE/OSGi). It is a
Gradle-managed subproject that still preserves the required standalone
Equinox launcher/runtime boundary.

## Build launcher

```bash
# preferred: prebuilt runtime archive (no local Eclipse SDK needed)
./gradlew :target-installer:targetInstallerLauncherJar -PruntimeZip=/path/to/eclipse-runtime.zip

# fallback: build runtime from an Eclipse SDK
./gradlew :target-installer:targetInstallerLauncherJar \
  -PeclipseSdk=/path/to/eclipse-sdk \
  -Pp2Repositories=https://download.eclipse.org/releases/2024-12
```

Output:
- `tools/target-installer/build/libs/target-installer-launcher.jar`

The legacy `scripts/build-launcher.sh` remains as an implementation detail for
the Gradle task during the transition.

## Run (self-contained install)

```bash
java -jar tools/target-installer/build/libs/target-installer-launcher.jar --cache=persistent -- \
  -profileId DummyProfile \
  -targetDefinition /path/to/KNIME-AP.target \
  -install-folder /path/to/output/install
```

Defaults:
- If `-install-folder` is provided and `-bundle-pool`/`-p2Path` are
  omitted, the launcher uses:
  - `-bundle-pool <install-folder>/bundle-pool`
  - `-p2Path <install-folder>/p2`
