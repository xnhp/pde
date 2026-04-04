# CLI Reference

Generated. Do not edit manually.
Source commit: `d1d5800`
Generated date: `2026-03-19`

## `pde`

```text
Usage: pde [-hV] [COMMAND]
PDE tooling CLI
  -h, --help      Show this help message and exit.
  -V, --version   Print version information and exit.
Commands:
  ide-init         Generate IDE project files
  compile          Compile PDE Java bundles
  format           Format Java sources via Eclipse formatter
  add-test         Append a test entry to launch config
  add-test-helper  Append a gateway helper test entry
  run, launch      Run a launch config
  target           Target platform commands (install, mirror)
  test             Run PDE test launch
  api-analyze      Run API analysis
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
Usage: pde api-analyze
Run API analysis
```

## `pde compile`

```text
Usage: pde compile
Compile PDE Java bundles
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

## `pde run`

```text
Usage: pde run
Run a launch config
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
Usage: pde target install
Resolve/prepare target platform state
```

## `pde target mirror`

```text
Usage: pde target mirror
Mirror update sites from a .target definition
```

## `pde test`

```text
Usage: pde test
Run PDE test launch
```

