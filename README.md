# Eclipse PDE tooling workspace

This repository contains:

- `intellij/` – the IntelliJ IDEA plugin (Eclipse PDE Partial)
- `core/` – reusable libraries (target platform indexing, launch planning, remote test protocol)
- `apps/` – headless tools built on top of `core/` (`pde-launch`, `pde-resolver-cli`, `pde-remote-runner`)

## Build

- Build everything: `./gradlew build`
- Run unit tests: `./gradlew check`

## IntelliJ plugin

- Run a sandbox IDE with the plugin: `./gradlew :intellij:runIde`

## Headless tools

- Build/install the launch tool: `./gradlew :pde-launch:installDist`
  - Binary: `apps/pde-launch/build/install/pde-launch/bin/pde-launch`
- Build/install the remote runner: `./gradlew :pde-remote-runner:installDist`
  - Binary: `apps/pde-remote-runner/build/install/pde-remote-runner/bin/pde-remote-runner`
- Build/install the resolver CLI: `./gradlew :pde-resolver-cli:installDist`
  - Binary: `apps/pde-resolver-cli/build/install/pde-resolver-cli/bin/pde-resolver-cli`

## Versioning

All published artifacts in this repo use semantic versioning via the `pluginVersion` Gradle property.
