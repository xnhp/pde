# PDE Tools

This repository contains:

- `intellij/` – the IntelliJ IDEA plugin (PDE Tools)
- `core/` – reusable libraries (target platform indexing, launch planning, remote test protocol)
- `apps/` – headless tools built on top of `core/` (`pde`, `pde-resolver-cli`, `pde-test-runner`)

## Build

- Build everything: `./gradlew build`
- Run unit tests: `./gradlew check`

## IntelliJ plugin

- Run a sandbox IDE with the plugin: `./gradlew :intellij:runIde`

## Headless tools

- Build/install the launch tool: `./gradlew :pde-launch:installDist`
  - Binary: `apps/pde-launch/build/install/pde/bin/pde`
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
