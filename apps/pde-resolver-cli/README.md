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

- Provide a `startupLevels.yaml` next to your config (or include it from your main YAML).
  Example:

  ```yaml
  startupLevels:
    org.eclipse.osgi: -1
    org.eclipse.equinox.common: 2
  ```

- The CLI does **not** read `.idea/eclipse-partial.xml` automatically; export/copy desired
  startup-level configuration into YAML for parity with IDE runs.

- Bundle whitelist handling is now schema-driven from YAML config; see the schema descriptions
  for supported fields and defaults.

### Target configuration

- Use `target` in your config to describe target platform resolution and installer/mirror
  behavior. Property-level details (including defaults) live in the schema.
- The profile registry path is derived from `target.p2Path` + `target.profileId`.
- To inspect the active schema path for your local installation, run `pde schema`.

### Remote debugging PDE tests

- Set `tests[].debug: true` in `launch.yaml` to start the PDE JUnit application with a JDWP
  agent listening on port `5005` (server=y, suspend=y). This only applies when
  `application` is `org.eclipse.pde.junit.runtime.coretestapplication` so normal launches
  stay unaffected. Attach IntelliJ’s Remote JVM Debug configuration to port `5005` to
  debug the test run.
- Set `launches[].debug: true` to enable JDWP for non-test launches using the same port.

### Workspace bundles

Example entry inside `launch.yaml`:

```
bundles:
  - path: ../workspace/org.example.bundle
    classRoots:
      - build/classes/java/main
      - build/classes/java/test
```

- Workspace-bundle property semantics are documented in the schema descriptions.
- The CLI does not build sources; class output directories must already contain `.class` files.

- `--root, -r <path>`
  - Root path to scan. Repeatable.
  - Accepts:
    - Eclipse SDK root (contains `plugins/`)
    - Target Definition profile dir (`*.profile`)
    - Plain bundle directory of JARs/dirs
    - Single JAR or exploded bundle directory
- `--cache-file, -c <file>`
  - Optional explicit cache file. If omitted, the library uses:
    - `$XDG_CACHE_HOME/pde-resolver/v1/index-<hash>.properties` or
    - `~/.cache/pde-resolver/v1/index-<hash>.properties`
- `--bsn, -b <symbolic-name>`
  - Optional filter to only list a specific BSN.
- `--json, -j`
  - Output JSON instead of plain text.

## Examples

- Index a local Eclipse SDK:
  - `pde-resolver-cli --root /opt/eclipse`
- Index a Target Definition profile directory:
  - `pde-resolver-cli --root /workspace/.metadata/.../TD.profile`
- Index multiple roots at once:
  - `pde-resolver-cli --root /opt/eclipse --root /path/to/extra/bundles`
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
