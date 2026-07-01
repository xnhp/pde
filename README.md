
# Overview

This project offers tooling to work on Eclipse PDE projects. Works well with an ["issue directory"](https://github.com/xnhp/issue) approach.

## CLI
This projects provides a CLI to facilitate the basic steps for working on PDE projects.

Basic configuration is put into `pde.yaml`:
```
target:
  definition: /home/ben/Desktop/ap-dev-setup/target/KNIME-AP-internal.target
  # installer is optional when using the packaged pde distribution
  bundlePool: /home/ben/Desktop/issues/bundle-pool/
  # optional: force already-installed target/workspace bundles onto compile/run/test classpaths
  extraBundles:
    - org.example.some.bundle
    - org.example.other.bundle@1.2.3

# the "workspace"
bundles:
    - path: knime-com-gateway/com.knime.gateway.executor
    - path: knime-com-gateway/com.knime.gateway.executor.tests
    - path: knime-core-ui/org.knime.core.ui
    - path: knime-core-ui/org.knime.core.ui.tests
      classRoots: # can override if needed
          - bin/eclipse
# corresponds to a PDE launch configuration
launches:
    - name: AP
      product: org.knime.product.KNIME_PRODUCT
      application: org.knime.product.KNIME_APPLICATION
      splash: org.knime.product
      vmArgs:
        - -ea
        - [...]
      env:
        KNIME_PROFILE: development
        FEATURE_FLAG: "true"
# run JUnit Plug-In tests
tests:
    - className: com.knime.gateway.executor.server.GatewayServerTest
      name: GatewayServerTest
      programArgs: []
      runner: junit5
      testPluginName: com.knime.gateway.executor
      vmArgs: []

```

Example commands are:

- `pde target install` -- install/update Target Platform state
- `pde compile` -- compile java sources (with TP/workspace dependency resolution)
- `pde run` -- start a PDE application (with TP/workspace dependency resolution)
- `pde test` -- run PDE JUnit-Plug-In tests
- `pde format` -- (experimental) invoke the Eclipse JDT code formatter
- `pde api-analyze` -- (experimental) run API analysis

The full CLI command reference is available directly in `--help` output
(`pde --help` and `pde <command> --help`).

Use `target.extraBundles` when a compile/run/test classpath needs bundles that are already present
in the target platform but are not selected by normal dependency resolution. It avoids editing the
`.target` file, but it does not install missing bundles or resolve dependencies of the forced bundle.

Use `target.pinnedVersions` when the target platform contains several versions of the same bundle
and pde should select a specific bundle version. Pins still use normal dependency/import checks:
invalid version strings fail config validation, out-of-range `Require-Bundle` pins are warned about,
and `Import-Package` providers must still export the requested package version.

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

Download release artifacts from the GitHub [releases](https://github.com/xnhp/pde/releases) page.

- CLI: See [CLI Quickstart](docs/cli-quickstart.md)
- IntelliJ Plugin: "Install plugin from disk" and select zip from releases page. More documentation can be provided if there is interest.

# See Also

- [Jetbrains feature request](https://youtrack.jetbrains.com/issue/IDEA-124520) for PDE support
- [Eclipse PDE repository](https://github.com/eclipse-pde/eclipse.pde)

# Repository Layout

This repository contains:

- `core/` – reusable libraries (target platform indexing, launch planning, remote test protocol)
  plus internal CLI engine/runtime modules (`:pde-launch-engine`, `:pde-remote-test-runtime`)
- `apps/` – headless CLI entrypoints (`:pde-cli` public, `:pde-format` internal)
- `intellij/` – IntelliJ IDEA plugin to support working with PDE projects


# Development

## Build

- Build everything: `./gradlew build`
- Run unit tests: `./gradlew check`
- Run a single `pde-cli` test: `./gradlew :pde-cli:test --tests cn.varsa.pde.launch.JdtlsInitTest`

## IntelliJ plugin

- Run a sandbox IDE with the plugin: `./gradlew :intellij:runIde`

## Headless tools

- Build/install the CLI: `./gradlew :pde-cli:installDist`
  - Binary: `apps/pde-launch/build/install/pde/bin/pde`
- IntelliJ/PDE project setup: `pde ide-init idea`
- JDT LS project setup: `pde ide-init jdtls` (Emacs/Eglot guide: `docs/jdtls-eglot.md`)

## Target installer

The target-installer lives under `tools/target-installer` and is built as a
Gradle-managed subproject. It remains a standalone Equinox/OSGi application at
runtime because Eclipse p2 provisioning requires OSGi services.

- Build the launcher jar: `./gradlew :target-installer:targetInstallerLauncherJar`
  - Root helper task: `./gradlew buildTargetInstallerLauncher`
  - Optional Gradle properties:
    - `-PruntimeZip=/path/to/eclipse-runtime.zip` uses a prebuilt runtime archive
      (preferred; no local Eclipse SDK required)
    - `-PeclipseSdk=/path/to/eclipse-sdk` provisions the runtime from a local
      Eclipse SDK when `runtimeZip` is not set
    - `-Pp2Repositories=https://download.eclipse.org/releases/2024-12` adds p2
      repositories for SDK-based runtime provisioning
  - `gradle.properties` provides local defaults for `eclipseSdk` and
    `p2Repositories`; override them with `-P...` when needed.
  - Output: `tools/target-installer/build/libs/target-installer-launcher.jar`
- The `pde` distribution includes this launcher jar under `lib/`, so
  `target.installer` can be omitted when running from the packaged CLI. Set
  `target.installer` or `-Dpde.targetInstaller=/path/to/target-installer-launcher.jar`
  only when using a custom launcher jar.

## Versioning

All published artifacts in this repo use semantic versioning via the `pluginVersion` Gradle property.

## Runtime requirements

- The `pde` CLI requires a Java 21 runtime (JRE or JDK) available on `PATH` (or via `JAVA_HOME`).

## Release (GitHub Releases)

Releases are produced via the **Release** GitHub Actions workflow:

1. Decide on the semantic version for this release (`X.Y.Z`). If you want the
   repository to track that version by default, update `pluginVersion` in
   `gradle.properties` in a separate commit (optional, the workflow can override
   the value at build time).
2. Open the **Actions → Release → Run workflow** dialog.
   - Enter the version (e.g. `0.2.0`).
   - Select whether to publish the CLI bundle, the IntelliJ plugin, or both.
3. The workflow checks out the tip of `knime`, runs
   `./gradlew check :pde-cli:distZip :intellij:buildPlugin -PpluginVersion=<version>`
   (authenticating against GitHub Packages with the workflow token), and produces
   the ZIP artifacts under `apps/pde-launch/build/distributions/` and
   `intellij/build/distributions/`.
4. For each selected product the workflow creates a tag (`cli/vX.Y.Z` or
   `ij/vX.Y.Z`) pointing at the triggering commit, creates a GitHub release, and
   uploads the corresponding ZIP asset.
5. After the workflow succeeds, confirm the release pages contain the correct
   artifacts. Use `gh release view <tag> --repo xnhp/pde --json assets --jq '.assets | length'`
   if you want to script the verification.

Notes:
- CLI and IntelliJ plugin tags remain separate so they can be released independently.
- The Release workflow only runs on demand (`workflow_dispatch`); normal CI jobs do not publish.
- Do not publish a release without the expected assets; users install
  directly from the GitHub release archives.
