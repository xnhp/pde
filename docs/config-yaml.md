# pde-resolver config.yaml

This document describes the YAML configuration consumed by the `pde-resolver-cli` (`pde-launch`, `pde-compile`, `pde-test`).

## File discovery

The CLI looks for a config file path provided on the command line. If omitted, it attempts to discover a `config.yaml` near the working directory (see `pde-resolver-cli` logs for the resolved path).

## Top-level fields

### includes
Optional list of config YAML files to merge before the current file. Paths are resolved relative to the current file.

Merge rules:
- Scalars and lists replace earlier values.
- Maps merge by key (later entries override).
- `launches` and `tests` merge by `name` (later entries override by name; new names append).

### issueId
Optional string used for labeling/logging.

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
- `installer`: required for `pde-launch target` (path to the installer config directory).

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
Optional map of environment variables set when running `pde-launch`.

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
```

- `repo`: absolute or relative path to the repository root.
- `bundles`: list of bundles. Each entry can be:
  - string: bundle name (uses default class root `bin`)
  - object: `{ name, classes }` where `classes` overrides the dev class roots.

Use the object form when the bundle’s resources live outside the default `bin` (e.g. `org.knime.core` needs `bin/eclipse` for `log4j` resources).

### workspaceModules
Explicit list of workspace modules.

```yaml
workspaceModules:
  - path: /abs/path/to/org.example.bundle
    classes:
      - bin
```

If `workspaceModules` is set, it overrides automatic expansion from `bundlesPerRepo` (unless you also use `extraWorkspaceModules`).

### extraWorkspaceModules
Additional workspace modules appended to the resolved list.

```yaml
extraWorkspaceModules:
  - path: /abs/path/to/org.example.bundle
    classes:
      - bin/eclipse
      - bin
```

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
- `name`: identifier used by `pde-launch`.
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
- `runner`: defaults to `junit4`; use `junit5` to add `-testLoaderClass org.eclipse.jdt.internal.junit5.runner.JUnit5TestLoader`
  and `-loaderpluginname org.eclipse.jdt.junit5.runtime` unless already present in `programArgs`.
- `env`: per-test environment variables (merged with top-level `env`).

## Practical notes

- If you want local sources and dev-classpath overrides, prefer the object form in `bundlesPerRepo`.
- `classes` paths are relative to the bundle directory.
- When in doubt, use absolute paths for `repo`, `path`, and `target.*` paths.
