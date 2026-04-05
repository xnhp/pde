
# Overview

This project offers tooling to work on Eclipse PDE projects.

## CLI
This projects provides a CLI to facilitate the basic steps for working on PDE projects. Example commands are:

- `pde target install` -- install/update Target Platform state
- `pde compile` -- compile java sources (with TP/workspace dependency resolution)
- `pde run` -- start a PDE application (with TP/workspace dependency resolution)
- `pde test` -- run PDE JUnit-Plug-In tests
- `pde format` -- (experimental) invoke the Eclipse JDT code formatter
- `pde api-analyze` -- (experimental) run API analysis

The full CLI command reference is generated from `--help` output into `docs/cli-reference.md`
as part of `:pde-launch:installDist`.

## IntelliJ integration

Tight integration into IntelliJ, containing all functionality of core/CLI apps, plus:
- full code navigation, autocomplete, etc
- linting for MANIFEST.MF files
- launch configurations
- GUI to configure target platform root
- bootstrap an IntelliJ project config based on the PDE project config (`pde ide-init idea`)
- Target platform configuration (Eclipse SDK, p2, target definitions, directory bundle roots) with indexing
- `MANIFEST.MF` language support: header parsing, completion, inspections, quick fixes
- `plugin.xml`/`.exsd` extension point model with completion and validation
- Eclipse application run configuration and workspace/module resolve actions

## LSP integration

To support arbitrary other editors (or agents), `pde ide-init jdtls` bootstraps project configuration to support LSP
integration using [jdt-ls](https://github.com/eclipse-jdtls/eclipse.jdt.ls). This has been tested only with specific
project setups and emacs.


# Getting Started

## Set up configuration `pde.yaml`

## Install target platform state: `pde target install`

If not otherwise specified, this will set up a p2 profile (usable as target platform state) in the current working
directory, based on a _shared_ bundle pool (see above). This means

1. You have to download dependencies only once
2. The individual profiles are still completely isolated

## Compile your sources: `pde compile`

<details>
<summary>
We have basic module-level change detection
</summary>
```
➜ 15:39 ben todo_NXT-4622-executor-to-serve-web-resources-dire pde compile
[INFO] Discovered launch config in /home/ben/Desktop/issues/todo_NXT-4622-executor-to-serve-web-resources-dire/pde.yaml and will use it.
[WARN] Using lowercase profile registry path: /home/ben/Desktop/issues/todo_NXT-4622-executor-to-serve-web-resources-dire/target/p2/org.eclipse.equinox.p2.engine/profileRegistry/profile.profile
[INFO] skipped com.knime.gateway.executor: Up-to-date; compile skipped
[INFO] skipped com.knime.gateway.executor.tests: Up-to-date; compile skipped
```
</details>

## Set up and run a PDE Application/Product
```
launches:
  - name: Analytics-Platform
    product: org.knime.product.KNIME_PRODUCT
    application: org.knime.product.KNIME_APPLICATION
    splash: org.knime.product
    vmArgs:
      - -ea
      [...]
```


## Set up and run unit tests

Configure a test in `pde.yaml` (see schema definition for guaranteed up-to-date info)
```
tests:
    - className: com.knime.gateway.executor.server.GatewayServerEarlyStartupTest
      debug: false
      name: foo
      programArgs: []
      runner: junit5
      testPluginName: com.knime.gateway.executor
      vmArgs: []
```

Then launch with `pde test` (runs all configured tests) or `pde test foo` to run a specific test
and observe nice output:
```
➜ 15:36 ben todo_NXT-4622-executor-to-serve-web-resources-dire pde test
[TEST] START testNeitherSet()
[PASS] testNeitherSet() (36ms)
[TEST] START testSystemPropertySet()
[PASS] testSystemPropertySet() (0ms)
[TEST] START testEnvVarSet()
[PASS] testEnvVarSet() (0ms)
[TEST] START testEnvVarTakesPrecedenceOverSystemProperty()
[PASS] testEnvVarTakesPrecedenceOverSystemProperty() (0ms)
[INFO] Remote test session finished at 2026-03-31T13:36:47.243668156Z
[INFO] Tests=4 failed=0 errors=0 elapsedMs=122
```
(Dont forget to compile first e.g. with `pde compile`)

---

# Skill/--help notes
- how to set up and run unit tests

---


# Repository Layout

This repository contains:

- `core/` – reusable libraries (target platform indexing, launch planning, remote test protocol)
- `apps/` – headless tools built on top of `core/` (`pde`, `pde-resolver-cli`, `pde-test-runner`)
- `intellij/` – IntelliJ IDEA plugin to support working with PDE projects


# Development

## Build

- Build everything: `./gradlew build`
- Run unit tests: `./gradlew check`
- Run a single `pde-launch` test: `./gradlew :pde-launch:test --tests cn.varsa.pde.launch.JdtlsInitTest`

## IntelliJ plugin

- Run a sandbox IDE with the plugin: `./gradlew :intellij:runIde`

## Headless tools

- Build/install the launch tool: `./gradlew :pde-launch:installDist`
  - Binary: `apps/pde-launch/build/install/pde/bin/pde`
- Build/install the formatter: `./gradlew :pde-format:installDist`
  - Binary: `apps/pde-format/build/install/pde-format/bin/pde-format`
- IntelliJ/PDE project setup: `pde ide-init idea`
- JDT LS project setup: `pde ide-init jdtls` (Emacs/Eglot guide: `docs/jdtls-eglot.md`)
- Build/install the remote runner: `./gradlew :pde-test-runner:installDist`
- Build/install the resolver CLI: `./gradlew :pde-resolver-cli:installDist`

## Target installer

The standalone target-installer lives under `tools/target-installer`.

- Build the launcher jar: `./gradlew buildTargetInstallerLauncher`
  - Configure either:
    - `runtimeZip=/path/to/eclipse-runtime.zip` (preferred; no local Eclipse SDK required), or
    - `eclipseSdk=/path/to/eclipse-sdk` (fallback)
  - Optional:
    - `p2Repositories=https://download.eclipse.org/releases/2024-12`
  - Output: `tools/target-installer/dist/target-installer-launcher.jar`

## Versioning

All published artifacts in this repo use semantic versioning via the `pluginVersion` Gradle property.
