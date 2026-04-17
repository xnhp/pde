# CLI Reference

Generated. Do not edit manually.
Source commit: `3226e9d`
Generated date: `2026-04-16`

## `pde`

```text
Usage: pde [-hV] [COMMAND]
PDE tooling CLI
  -h, --help      Show this help message and exit.
  -V, --version   Print version information and exit.
Commands:
  ide-init                   Generate IDE project files
  compile                    Compile PDE Java bundles
  format                     Format Java sources via Eclipse formatter
  add-test                   Append a test entry to launch config
  add-test-helper            Append a gateway helper test entry
  run                        Run a launch config
  launch                     Run a launch config
  target                     Target platform commands (install, mirror)
  test                       Run PDE test launch
  api-analyze, api-analyzer  Run API analysis
  schema                     Print the active pde schema path
```

## `pde add-test`

```text
Usage: pde add-test
Append a test entry to launch config
```

## `pde add-test-helper`

```text
Usage: pde add-test-helper
Append a gateway helper test entry
```

## `pde api-analyze`

```text
Usage: pde api-analyze [-hvV] [--debug] [--fail-on-error]
                       [--application=String] [--baseline-list=String]
                       [--baseline-root=String] [--config=String]
                       [--dependency-list=String] [--jdt-compliance=String]
                       [--log=String] [--log-level=String] [configPos]
Run API analysis
      [configPos]            Launch config YAML
      --application=String   API analyzer application id
      --baseline-list=String Baseline list output path (defaults to
                               api-analyzer/baseline-list.txt)
      --baseline-root=String Baseline target root, profile path, or .target
                               file (defaults to target.install, target.p2Path,
                               or target profile)
      --config=String        Path to launch config YAML
      --debug                Enable debug logging
      --dependency-list=String
                             Dependency list output path (defaults to
                               api-analyzer/dependencies-list.txt)
      --fail-on-error        Fail when API errors are detected
  -h, --help                 Show this help message and exit.
      --jdt-compliance=String
                             Override JDT compliance (uses temp project copy)
      --log=String           Redirect launcher output to file
      --log-level=String     Log level (trace, debug, info, warn, error)
  -v, --verbose              Enable verbose logging
  -V, --version              Print version information and exit.
```

## `pde compile`

```text
Usage: pde compile [-hV] [--debug] [--execute] [--full-rebuild] [--json]
                   [--bundles-info-out=String] [--config=String]
                   [--framework=String] [--output-root=String]
                   [--results-json=String] [--runtime-out=String] [-w=String]
                   [configPos]
Compile PDE Java bundles
      [configPos]            YAML launch configuration (positional)
      --bundles-info-out=String
                             Write bundles.info reflecting compiled workspace
                               outputs
      --config=String        YAML launch configuration
      --debug                Emit debug info (lines/vars/source)
      --execute              Run ECJ compilation (default when using a launch
                               config)
      --framework=String     Framework BSN
      --full-rebuild         Force full rebuild of all workspace bundles (skip
                               incremental cache)
  -h, --help                 Show this help message and exit.
      --json                 Emit compile specs as JSON
      --output-root=String   Override workspace bundle output dir (relative to
                               module root, e.g., bin)
      --results-json=String  Write compile results (when --execute) to JSON file
      --runtime-out=String   Write config.ini/dev.properties/bundles.info for
                               compiled outputs under this directory
  -V, --version              Print version information and exit.
  -w, --workspace=String     Workspace bundle directory (repeatable)
```

## `pde format`

```text
Usage: pde format
Format Java sources via Eclipse formatter
```

## `pde ide-init`

```text
Usage: pde ide-init [-hV] [COMMAND]
Generate IDE project files
  -h, --help      Show this help message and exit.
  -V, --version   Print version information and exit.
Commands:
  idea   Generate IntelliJ project
  jdtls  Generate .project/.classpath for JDT LS
```

## `pde ide-init idea`

```text
Usage: pde ide-init idea
Generate IntelliJ project
```

## `pde ide-init jdtls`

```text
Usage: pde ide-init jdtls
Generate .project/.classpath for JDT LS
```

## `pde launch`

```text
Usage: pde launch [-hvV] [--debug] [--dry-run] [--osgiDebug]
                  [--application=String] [--config=String] [--dev-prop=String]
                  [--framework=String] [--log=String] [--log-level=String]
                  [-o=String] [--product=String] [--splash=String] [-t=String]
                  [-w=String] [configPos] [launchPos]
Run a launch config
      [configPos]            YAML launch configuration (positional)
      [launchPos]            Launch name (optional, from launches entry)
      --application=String   Application identifier
      --config=String        YAML launch configuration (supports launches/tests)
      --debug                Enable JDWP for launch JVM
      --dev-prop=String      Dev properties entry in form bsn=path1,path2
      --dry-run              Parse configuration only
      --framework=String     Framework BSN
  -h, --help                 Show this help message and exit.
      --log=String           Write application stdout/stderr to log file
      --log-level=String     Logging level (error|warn|info|debug|trace)
  -o, --output=String        Output directory for config.ini/bundles.info/dev.
                               properties
      --osgiDebug            Enable OSGi debug output (-debug)
      --product=String       Product identifier
      --splash=String        Splash bundle symbolic name
  -t, --target-root=String   Target root (repeatable)
  -v, --verbose              Enable INFO logging
  -V, --version              Print version information and exit.
  -w, --workspace=String     Workspace bundle directory (repeatable)
```

## `pde run`

```text
Usage: pde run [-hvV] [--debug] [--dry-run] [--osgiDebug]
               [--application=String] [--config=String] [--dev-prop=String]
               [--framework=String] [--log=String] [--log-level=String]
               [-o=String] [--product=String] [--splash=String] [-t=String]
               [-w=String] [configPos] [launchPos]
Run a launch config
      [configPos]            YAML launch configuration (positional)
      [launchPos]            Launch name (optional, from launches entry)
      --application=String   Application identifier
      --config=String        YAML launch configuration (supports launches/tests)
      --debug                Enable JDWP for launch JVM
      --dev-prop=String      Dev properties entry in form bsn=path1,path2
      --dry-run              Parse configuration only
      --framework=String     Framework BSN
  -h, --help                 Show this help message and exit.
      --log=String           Write application stdout/stderr to log file
      --log-level=String     Logging level (error|warn|info|debug|trace)
  -o, --output=String        Output directory for config.ini/bundles.info/dev.
                               properties
      --osgiDebug            Enable OSGi debug output (-debug)
      --product=String       Product identifier
      --splash=String        Splash bundle symbolic name
  -t, --target-root=String   Target root (repeatable)
  -v, --verbose              Enable INFO logging
  -V, --version              Print version information and exit.
  -w, --workspace=String     Workspace bundle directory (repeatable)
```

## `pde schema`

```text
Usage: pde schema
Print the active pde schema path
```

## `pde target`

```text
Usage: pde target [-hV] [COMMAND]
Target platform commands (install, mirror)
  -h, --help      Show this help message and exit.
  -V, --version   Print version information and exit.
Commands:
  install  Resolve/prepare target platform state
  mirror   Mirror update sites from a .target definition
```

## `pde target install`

```text
Usage: pde target install [-hvV] [--debug] [--config=String] [--launch=String]
                          [--log=String] [--log-level=String] [configPos]
Resolve/prepare target platform state
      [configPos]          YAML launch configuration (positional)
      --config=String      YAML launch configuration
      --debug              Enable DEBUG logging
  -h, --help               Show this help message and exit.
      --launch=String      Installer launch name (defaults to 'install' if
                             present)
      --log=String         Write application stdout/stderr to log file
      --log-level=String   Logging level (error|warn|info|debug|trace)
  -v, --verbose            Enable INFO logging
  -V, --version            Print version information and exit.
```

## `pde target mirror`

```text
Usage: pde target mirror [-hvV] [--artifacts-only] [--debug] [--metadata-only]
                         [--config=String] [-d=String] [--log=String]
                         [--log-level=String] [--write-mode=String] [configPos]
Mirror update sites from a .target definition
      [configPos]            YAML launch configuration (positional)
      --artifacts-only       Mirror artifacts only
      --config=String        YAML launch configuration
  -d, --destination=String   Destination repository path or URI
      --debug                Enable DEBUG logging
  -h, --help                 Show this help message and exit.
      --log=String           Write application stdout/stderr to log file
      --log-level=String     Logging level (error|warn|info|debug|trace)
      --metadata-only        Mirror metadata only
  -v, --verbose              Enable INFO logging
  -V, --version              Print version information and exit.
      --write-mode=String    Write mode (clean)
```

## `pde test`

```text
Usage: pde test [-hvV] [--debug] [--debugJVM] [--no-color] [--osgiDebug]
                [--quiet] [--config=String] [--exclude=String]
                [--forward-log=String] [--listen-host=String]
                [--listen-port=Int] [--log=String] [--log-level=String]
                [--port-range=String] [--report=String] [--timeout=Int]
                [configPos] [testPos]
Run PDE test launch
      [configPos]            YAML launch configuration (positional)
      [testPos]              Test name (optional, defaults to first tests entry)
      --config=String        YAML launch configuration
      --debug                Enable DEBUG logging
      --debugJVM             Enable JDWP for test JVM (equivalent to tests[].
                               debug=true)
      --exclude=String       Regex filter to exclude tests
      --forward-log=String   Forward log in form label=path
  -h, --help                 Show this help message and exit.
      --listen-host=String   Host to bind
      --listen-port=Int      Fixed port to bind
      --log=String           Write application stdout/stderr to log file
      --log-level=String     Logging level (error|warn|info|debug|trace)
      --no-color             Disable ANSI colors in console logs
      --osgiDebug            Enable OSGi debug output (-debug)
      --port-range=String    Inclusive port range start-end
      --quiet                Suppress console test logs
      --report=String        Reporting sink (teamcity, junit-xml:/path)
      --timeout=Int          Seconds to wait for PDE connection
  -v, --verbose              Enable INFO logging
  -V, --version              Print version information and exit.
```

