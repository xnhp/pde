# pde-resolver-cli

Small command‑line tool to index Eclipse target platform roots using the
shared resolver library and its on‑disk cache.

## Build and run

- Run with Gradle (no install):
  - `./gradlew :pde-resolver-cli:run --args "--root /path/to/sdk"`
- Create a local install (shell script under `build/install`):
  - `./gradlew :pde-resolver-cli:installDist`
  - `./build/install/pde-resolver-cli/bin/pde-resolver-cli --root /path/to/sdk`

## Usage

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

