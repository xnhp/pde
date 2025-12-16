# pde-resolver-cli

Small command‑line tool to index Eclipse target platform roots using the
shared resolver library and its on‑disk cache.

## Build and run

- Run with Gradle (no install):
  - `./gradlew :pde-resolver-cli:run --args "--root /path/to/sdk"`
- Create a local install (shell script under `build/install`):
  - `./gradlew :pde-resolver-cli:installDist`
  - `./apps/pde-resolver-cli/build/install/pde-resolver-cli/bin/pde-resolver-cli --root /path/to/sdk`

## Usage

### Startup level data

- Provide a `startupLevels.yaml` next to your `launch.yaml` (or point `startupLevelsFile` to
  another file). Example:

  ```yaml
  startupLevels:
    org.eclipse.osgi: -1
    org.eclipse.equinox.common: 2
  ```

- The CLI does **not** read `.idea/eclipse-partial.xml` automatically; explicitly export or
  copy the desired startup-level configuration into `startupLevels.yaml` if you want parity
  with your IDE run configuration. Legacy `startupLevels.xml` files are still understood but
  will eventually be phased out.

- Bundle whitelist (“Advanced” tab) can be shared the same way: create a plain text file
  `whitelist.txt` next to `launch.yaml` (or set `whitelistFile`). Each non-empty, non-comment
  line should contain one prefix such as `org.eclipse.io`. The CLI unions those entries with
  the `whitelist` array defined in YAML; if both are missing it falls back to the default
  trio (`org.eclipse.jdt.annotation`, `org.eclipse.io`, `org.eclipse.swt`).

### Target file

- Set `targetFile: path/to/Your.target` in `launch.yaml`. The CLI reads this file to pick up
  the VM/program arguments defined by the target definition when `inheritTargetArgs` is set
  to `true` (default). Set `inheritTargetArgs: false` if you want to ignore the `.target`
  file’s arguments and rely solely on the `additionalVmArgs` / `programArgs` arrays in YAML. For backward compatibility you can still spell it `vmArgs`, but future configs should migrate to `additionalVmArgs`.

### Profile path

- Set `profilePath: path/to/profileRegistry/MyProfile.profile` to tell the resolver which
  p2 profile to launch against. The CLI follows the profile’s `org.eclipse.equinox.p2.cache`
  pointer automatically, so you no longer need to add bundle pool directories explicitly.

### Remote debugging PDE tests

- Set `debugTests: true` in `launch.yaml` to start the PDE JUnit application with a JDWP
  agent listening on port `5005` (server=y, suspend=y). This only applies when
  `application` is `org.eclipse.pde.junit.runtime.coretestapplication` so normal launches
  stay unaffected. Attach IntelliJ’s Remote JVM Debug configuration to port `5005` to
  debug the test run.

### Workspace modules

Example entry inside `launch.yaml`:

```
workspaceModules:
  - path: ../workspace/org.example.bundle
    classes:
      - build/classes/java/main
      - build/classes/java/test
```

- `path` must point to a built bundle (directory or exploded JAR containing
  `META-INF/MANIFEST.MF`). Paths can be relative to `launch.yaml`.
- `classes` is optional; if omitted we default to `build/classes/java/main` and
  `out/production`. These directories must already contain compiled `.class` files—the CLI
  does not build sources.

- `--root, -r <path>`
  - Root path to scan. Repeatable.
  - Accepts:
    - Eclipse SDK root (contains `plugins/`)
    - Target Definition profile dir (`*.profile`)
    - Plain bundle directory of JARs/dirs
    - Single JAR or exploded bundle directory
- `--workspace, -w <path>`
  - Workspace bundle roots (repeatable). Use with `--plan` to include
    local modules. Each path should contain `META-INF/MANIFEST.MF` (either
    directory bundle or built JAR).
- `--cache-file, -c <file>`
  - Optional explicit cache file. If omitted, the library uses:
    - `$XDG_CACHE_HOME/pde-resolver/v1/index-<hash>.properties` or
    - `~/.cache/pde-resolver/v1/index-<hash>.properties`
- `--bsn, -b <symbolic-name>`
  - Optional filter to only list a specific BSN.
- `--json, -j`
  - Output JSON instead of plain text.
- `--plan`
  - Resolve workspace bundles against the target index (same logic as the
    IDE resolver) and print the bundles that would appear in a launch plan.

## Examples

- Index a local Eclipse SDK:
  - `pde-resolver-cli --root /opt/eclipse`
- Index a Target Definition profile directory:
  - `pde-resolver-cli --root /workspace/.metadata/.../TD.profile`
- Show launch plan bundles for workspace modules:
  - `pde-resolver-cli --plan --root /opt/eclipse --workspace /home/ben/git-repositories/workshop-intellij-setup/org.knime.base.treeensembles2`
- JSON output filtered to a bundle:
  - `pde-resolver-cli --root /opt/eclipse --bsn org.eclipse.osgi --json`

## Output

- Plain text:
  - First line shows data source: `SCANNED` or `CACHED` and bundle count.
  - Subsequent lines list: `bsn@version -> path`
- JSON:
  - `{ "source": "CACHED|SCANNED", "count": N, "bundles": [
        {"bsn": "...", "version": "...", "path": "..."}, ... ] }`

## Notes

- The CLI uses the same cached builder as the IDE plugin
  (`TargetPlatformCache.buildWithCache`).
- Profiles resolve their bundle pool automatically using the profile’s
  `org.eclipse.equinox.p2.cache` value.
