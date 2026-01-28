# AGENTS.md

This repository is an Eclipse PDE tooling workspace. It provides an IntelliJ IDEA plugin plus headless tools for
resolving Eclipse target platforms, planning launches, and running remote tests.

## Workflow

- After each (successful) change, commit the changes to git.

## Architecture and main contents

- `intellij/`: IntelliJ IDEA plugin implementation (Eclipse PDE Partial).
- `core/`: shared libraries for target platform indexing, launch planning, and the remote test protocol.
- `apps/`: headless CLI tools built on `core/`:
  - `pde-launch`: launch planning/runtime assembly for PDE-style runs.
  - `pde-resolver-cli`: target platform resolver and config-driven inputs.
  - `pde-remote-runner`: remote test runner (protocol in `core`).
- `docs/`: documentation, including `config-yaml.md` for resolver configuration.
- Gradle build: root `build.gradle.kts`, `settings.gradle.kts`.

## Typical use-cases

- Run and debug the IntelliJ plugin in a sandbox IDE.
- Resolve and validate an Eclipse target platform using `pde-resolver-cli`.
- Launch an Eclipse/OSGi application with specific bundles and startup levels.
- Run PDE/JUnit-style tests via the remote runner.
- Integrate local workspace bundles with target platform resolution.

## Getting started commands

- Build everything: `./gradlew build`
- Run unit tests: `./gradlew check`
- Run IntelliJ sandbox IDE: `./gradlew :intellij:runIde`
- Install headless tools:
  - `./gradlew :pde-launch:installDist`
  - `./gradlew :pde-resolver-cli:installDist`
  - `./gradlew :pde-remote-runner:installDist`

## Key configs and docs

- Resolver/launch config reference: `docs/config-yaml.md`
- Project overview: `README.md`
