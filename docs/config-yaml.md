# pde-resolver pde.yaml

This document describes the YAML configuration consumed by the `pde` CLI (`pde run`, `pde compile`, `pde test`, `pde api-analyze`).

## File discovery

The CLI looks for a config file path provided on the command line. If omitted, it attempts to discover a `pde.yaml`, `launch.yaml`, `pde.yaml`, or `pde-launch.yaml` near the working directory (see logs for the resolved path).

## Top-level fields

### includes
Optional list of config YAML files to merge before the current file. Paths are resolved relative to the current file.

Merge rules:
- Scalars and lists replace earlier values.
- Maps merge by key (later entries override).
- `launches` and `tests` merge by `name` (later entries override by name; new names append).

### issueId
Optional string used for labeling/logging.

### branch
Optional string used by `pde worktrees-init` to select a git branch for each repo (accepts `branch` or `origin/branch`).

### baseReposPath
Required string for `pde worktrees-init` that points at the base git clones. Worktrees are created from
`<baseReposPath>/<repo>` into the issue directory.

### branchName
Optional string used for labeling/logging.

### relatedNotes
Optional list of strings; no functional impact.

### target
Target platform and installer configuration.

Fields:
- `definition`: optional path to a target definition (`.target`). If omitted, a `.target` file is discovered in the issue directory.
- `profile-id`: optional profile id (default `profile`).
- `p2-path`: optional path to the p2 area (default `./target/p2`).
- `install`: optional install folder (default `./target/install`).
- `bundle-pool`: optional bundle pool (default `./target/bundle-pool`).
- `installer`: required for `pde target install` (path to the target-installer launcher JAR).
- `mirror.destination`: required for `pde target mirror` (path or URI of local mirror).
- `mirror.write-mode`: optional write mode (`clean` supported; otherwise ignored).
- `mirror.include-metadata`: optional boolean to mirror metadata (default `true`).
- `mirror.include-artifacts`: optional boolean to mirror artifacts (default `true`).

Notes:
- `pde target install` executes the launcher JAR directly (`java -jar ... --cache=persistent -- ...`).
- The target installer resolves relative paths against its runtime cache directory, not the CLI working directory, so prefer absolute paths for `target.*` values.
- `pde jdtls-init` requires a `target` section with a resolved profile (`target.profile-id` + `target.p2-path`). It does not fall back to bundle pools or install roots.

Example:

```yaml
target:
  installer: /abs/path/to/target-installer-launcher.jar
  definition: /abs/path/to/KNIME-AP-internal.target
  install: /abs/path/to/install-folder
  bundle-pool: /abs/path/to/bundle-pool
  p2-path: /abs/path/to/install-folder/p2
  mirror:
    destination: /abs/path/to/local-mirror
    write-mode: clean
```

### product
Optional product id for launching (e.g. `org.knime.product.KNIME_PRODUCT`).

### application
Optional application id for launching (e.g. `org.knime.product.KNIME_APPLICATION`).

### productFiles
Optional list of `.product` file paths to derive startup levels.

### startupLevelsFile
Optional path to a startup levels file; merged with defaults and product levels.

### startupLevels
Map of bundle symbolic name (BSN) -> start level. Merged with defaults.

### whitelist / whitelistFile
Bundle resolution whitelist prefixes (used by the resolver). Use `whitelistFile` to supply a file with one prefix per line.

### targetModules
Optional list of BSNs to force-load from target platform.

### nonPdeBundles
List of bundles that should *not* be resolved from workspace (even if listed elsewhere).

### inheritTargetArgs
Whether launch inherits target platform arguments (default `true`).

### env
Optional map of environment variables set when running `pde`.

### dataDir / configDir / workDir
Optional directories to control launch locations (P2 data area, config, work dir).

### cleanRuntime
If `true`, the generated runtime layout is cleaned before launch.

## Workspace inputs

### bundlesPerRepo
Declare bundles to resolve from local repos. This is the most common entry point for “use local sources”.

```yaml
bundlesPerRepo:
  - repo: /abs/path/to/repo
    bundles:
      - org.example.bundle
      - name: org.example.bundle.with.classes
        classes:
          - bin/eclipse
          - bin
    nonPdeBundles:
      - org.example.nonpde.bundle
```

- `repo`: absolute or relative path to the repository root. Relative paths resolve against the config file directory.
  When running `pde jdtls-init --issue-dir`, relative repo paths resolve against the issue directory instead.
  Make sure your editor/JDT LS project root also points at the issue directory so the
  generated metadata and bundle paths line up with the config.
- `bundles`: list of bundles. Each entry can be:
  - string: bundle name (uses default class root `bin`)
  - object: `{ name, classes }` where `classes` overrides the dev class roots.
- `nonPdeBundles`: optional list of bundle directories that should be treated as non-PDE (used by `pde worktrees-init`; ignored for workspace module resolution).

Use the object form when the bundle’s resources live outside the default `bin` (e.g. `org.knime.core` needs `bin/eclipse` for `log4j` resources).

## Launch definitions

### launches
List of named launch configs. Each entry can override product/application and add args.

```yaml
launches:
  - name: AP
    product: org.knime.product.KNIME_PRODUCT
    application: org.knime.product.KNIME_APPLICATION
    splash: org.knime.product
    debug: false
    env:
      GDK_BACKEND: x11
    programArgs:
      - -clean
    vmArgs:
      - -Xmx2048m
```

Fields:
- `name`: identifier used by `pde`.
- `product`, `application`, `splash`: override top-level values.
- `debug`: enable JVM debug (CLI may add JDWP).
- `env`: per-launch environment variables (merged with top-level `env`).
- `programArgs`: appended to the launch command.
- `vmArgs`: appended to the JVM command line.

## Test definitions

### tests
List of named test configs for PDE/JUnit runs.

```yaml
tests:
  - name: GatewayDefaultServiceTests
    testpluginname: org.knime.gateway.impl
    classname: org.knime.gateway.impl.webui.service.GatewayDefaultServiceTests
    runner: junit4
    debug: false
    env:
      GDK_BACKEND: x11
    programArgs:
      - -clean
    vmArgs:
      - -Xmx2048m
```

Keys map to PDE/JUnit options:
- `testpluginname` -> `-testpluginname`
- `classname` -> `-classname`
- `runner`: defaults to `junit4` which adds `-testLoaderClass org.eclipse.jdt.internal.junit4.runner.JUnit4TestLoader`
  and `-loaderpluginname org.eclipse.jdt.junit4.runtime` unless already present in `programArgs`.
  Use `junit5` to add `-testLoaderClass org.eclipse.jdt.internal.junit5.runner.JUnit5TestLoader`
  and `-loaderpluginname org.eclipse.jdt.junit5.runtime` unless already present in `programArgs`.
- `env`: per-test environment variables (merged with top-level `env`).

## Practical notes

- If you want local sources and dev-classpath overrides, prefer the object form in `bundlesPerRepo`.
- `classes` paths are relative to the bundle directory.
- `classes` must point at the compiled output directory. If `build.properties`
  sets `output..=bin/eclipse`, set `classes: [bin/eclipse]` so `pde run` finds
  the compiled output.
- `pde run` and `pde test` do not compile sources; they require `.class` files
  in the configured class roots (from JDT LS or `pde compile`).
- When in doubt, use absolute paths for `repo` and `target.*` paths.

## JDT LS workspace setup

Use `pde jdtls-init` to generate `.project` and `.classpath` files for workspace bundles
defined by this config. `target` must be configured and point at a resolved target profile.
See `docs/jdtls-eglot.md` for Emacs/Eglot setup details.

## API analysis (pde api-analyze)

The API analyzer runs once per workspace bundle resolved from the config and compares each bundle against the baseline.

Requirements:
- `bundlesPerRepo` must resolve at least one workspace bundle.
- `target.definition` should point at the target definition used to build the dependency list.
- `--baseline-root` is optional; if omitted, the CLI falls back to `target.install`, then `target.p2Path`, then the resolved target profile path.
- `--baseline-root` can point to a `.target` file (for API baselines) and will be passed directly to the analyzer.

Outputs:
- `api-analyzer/dependencies-list.txt` (from the target platform, excluding workspace bundles).
- `api-analyzer/baseline-list.txt` (from `--baseline-root`).

Example:

```yaml
target:
  definition: /abs/path/to/knime.target

bundlesPerRepo:
  - repo: /abs/path/to/knime
    bundles:
      - org.knime.gateway.impl
```

```bash
pde api-analyze --config /abs/path/to/pde.yaml --baseline-root /abs/path/to/API-Baseline.target
```
