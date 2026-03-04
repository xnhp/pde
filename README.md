
This repository contains:

- `core/` – reusable libraries (target platform indexing, launch planning, remote test protocol)
- `apps/` – headless tools built on top of `core/` (`pde`, `pde-resolver-cli`, `pde-test-runner`)
- `intellij/` – IntelliJ IDEA plugin to support working with PDE projects

# CLI
This projects provides a CLI to facilitate the basic steps for working on PDE projects.

- `pde target-install` -- install/update Target Platform state
- `pde target-mirror` -- mirror update sites from a target definition
- `pde compile` -- compile java sources (with TP/workspace dependency resolution)
- `pde run` -- start a PDE application (with TP/workspace dependency resolution)
- `pde test` -- run PDE JUnit-Plug-In tests
- `pde format` -- invoke the Eclipse JDT code formatter

In addition, the CLI offers convenience tools to keep the workspace and
development setup for different To-Dos (issues) completely isolated.

- `pde worktrees-init` -- create worktrees and sparse-checkout bundles
- `pde issue-new` -- auto-generate workspace config
- `pde foreach-repo` -- run shell command in each configured repo
- `pde ij-init` -- auto-intialise IntelliJ project configuration
- `pde jdtls-init` -- generate configuration files to enable LSP integration facilitated by [jdt-ls](https://github.com/eclipse-jdtls/eclipse.jdt.ls)


# Development

## Build

- Build everything: `./gradlew build`
- Run unit tests: `./gradlew check`
- Run a single `pde-launch` test: `./gradlew :pde-launch:test --tests cn.varsa.pde.launch.JdtlsInitTest`

## IntelliJ plugin

- Run a sandbox IDE with the plugin: `./gradlew :intellij:runIde`
- IntelliJ IDEA support for PDE projects, including:
  - Target platform configuration (Eclipse SDK, p2, target definitions, directory bundle roots) with indexing
  - PDE facet + module library resolvers for workspace, manifest, and build classpaths
  - `MANIFEST.MF` language support: header parsing, completion, inspections, quick fixes
  - `plugin.xml`/`.exsd` extension point model with completion and validation
  - Eclipse application run configuration and workspace/module resolve actions

## Headless tools

- Build/install the launch tool: `./gradlew :pde-launch:installDist`
  - Binary: `apps/pde-launch/build/install/pde/bin/pde`
- Build/install the formatter: `./gradlew :pde-format:installDist`
  - Binary: `apps/pde-format/build/install/pde-format/bin/pde-format`
- IntelliJ/PDE project setup: `pde ij-init`
- JDT LS project setup: `pde jdtls-init` (Emacs/Eglot guide: `docs/jdtls-eglot.md`)
- Build/install the remote runner: `./gradlew :pde-test-runner:installDist`
- Build/install the resolver CLI: `./gradlew :pde-resolver-cli:installDist`

## Target installer

The standalone target-installer lives under `tools/target-installer`.

- Build the launcher jar: `./gradlew buildTargetInstallerLauncher`
  - Requires Gradle properties in `~/.gradle/gradle.properties`:
    - `eclipseSdk=/path/to/eclipse-sdk`
    - `p2Repositories=https://download.eclipse.org/releases/2024-12`
  - Output: `tools/target-installer/dist/target-installer-launcher.jar`

## Versioning

All published artifacts in this repo use semantic versioning via the `pluginVersion` Gradle property.
