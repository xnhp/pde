Resolver Extraction Summary

Scope
  - Extract PDE dependency resolution into a standalone library that both
    the IntelliJ plugin and future CLI tools can consume without behaviour
    changes.

Responsibility 1 – Parse Target Platform
  Core implementation
    - `pde-resolver` module supplies:
      - `cn.varsa.pde.resolver.manifest.BundleManifest` shared model.
      - `cn.varsa.pde.resolver.support.*` helpers for strings and versions.
      - `cn.varsa.pde.resolver.index.TargetPlatformIndex` scanning Eclipse SDK
        roots, target definition profiles, and loose directories/files.
      - `cn.varsa.pde.resolver.index.ProfileUtils` helpers such as
        `findProfileFile`, `getBundlePoolPath`, and `mapProfileFile`.
      - `cn.varsa.pde.resolver.features.FeatureScanner` for SDK, P2, and
        target profile features.
      - `cn.varsa.pde.resolver.index.TargetPlatformCache` for fingerprinted,
        persisted bundle snapshots.
  Plugin integration
    - Providers (`DirectoryBundleProvider`, `EclipseSDKBundleProvider`,
      `EclipseP2BundleProvider`, `TargetDefinitionBundleProvider`) delegate
      to the shared index and scanners.
    - Legacy plugin `ProfileUtils` has been removed.
  Tests
    - `BundleManifestParseTest`, `ProfileUtilsTest`,
      `TargetPlatformIndexTest`, `TargetPlatformIndexProfileTest`, and
      `FeatureScanner*Test`.

Responsibility 2 – Resolve Workspace Bundles
  Core implementation
    - `cn.varsa.pde.resolver.algo.Resolver` accepts a `TargetPlatformIndex`,
      workspace descriptors, an entry bundle, and `ResolveOptions`.
    - Produces a `ResolveResult` with ordered bundles (workspace overrides
      target), `requires` and `imports` diagnostics, and unresolved items.
    - Reuses manifest parsing helpers and target cache for deterministic,
      performant lookups.
  Plugin orchestration
    - `PdeModuleRuntimeLibraryResolver` builds workspace descriptors, invokes
      the core resolver, and materialises project libraries on the EDT while
      reporting unresolved bundles through `PdeNotifier`.
    - `PdeModuleFragmentLibraryResolver` wires fragment hosts using resolver
      output and orders fragments before hosts.
    - `BundleManifestExt` provides lightweight adapters around core models.
  Supporting launch stack
    - Core `pde-resolver.launch` package:
      - `LauncherModels.kt` defines `LauncherOptions`, `BundleStartSpec`,
        `LauncherPlan`, and `LaunchContext`.
      - `LauncherAssembler` converts a `ResolveResult` into a launch plan and
        honours workspace precedence.
      - `Renderers` expose `ConfigIniRenderer`, `BundlesInfoRenderer`, and
        `DevPropertiesRenderer`.
    - IntelliJ adapters:
      - `LauncherPlanBuilder` gathers target libraries and workspace dev
        modules via `ConfigService` and produces the core plan/context.
      - `PDETargetRunConfiguration` runs validation, invokes the plan
        builder, writes renderer output, and prepares the JVM parameters.
      - `ConfigService` defines the contract for manifests, startup levels,
        dev module mapping, and runtime paths.
    - Supporting services (`TargetDefinitionService`,
      `PluginTargetIndexService`, `BundleManifestCacheService`, `PDEFacet`,
      `DevModule`) feed data into the launch flow.

Responsibility 3 – Assemble Launch Configuration
  Core assets
    - Launcher models, assembler, and renderers live in `pde-resolver` and
      are covered by unit tests.
  Plugin integration
    - Run configuration code writes launch artefacts via core helpers after
      mapping project state through `ConfigService`.
  Status
    - Legacy `LaunchConfigGenerator` replaced by the shared launcher stack.
  Next focus
    - Ensure launch orchestration extracted in Responsibility #2 remains a
      thin adapter layer inside the plugin once core planners exist.

Launch Logic Extraction Plan (Responsibility #3)
  1. Define core inputs
     - Introduce a typed `LaunchEnvironment` DTO in `pde-resolver` mirroring
       the fields currently surfaced by `ConfigService`.
  2. Move launch aggregation into core
     - Port `LauncherPlanBuilder.build` to a core orchestrator that consumes
       `LaunchEnvironment`, returns `LauncherPlan` plus `LaunchContext`, and
       preserves workspace precedence and startup semantics.
  3. Expose rendering helpers
     - Extend existing renderers to accept simple data containers so they
       can be invoked by the IDE and a CLI without additional adapters.
  4. Adapt the IntelliJ plugin
     - Replace `LauncherPlanBuilder` with a thin adapter translating
       `ConfigService` into `LaunchEnvironment`, delegating plan creation and
       rendering to the core module.
  5. Provide CLI-ready surfaces
     - Add a facade in `pde-resolver` that accepts filesystem inputs,
       constructs a `LaunchEnvironment`, and emits launch artefacts through
       injectable writers for future CLI consumption.
  6. Guard behaviour parity
     - Back the refactor with regression tests covering workspace override,
       startup levels, splash paths, framework selection, and generated
       `config.ini`, `bundles.info`, and `dev.properties` payloads.


Architecture Reference
  Package layout
    - `cn.varsa.pde.resolver.manifest`: manifest models and header parsing.
    - `cn.varsa.pde.resolver.support`: shared utilities.
    - `cn.varsa.pde.resolver.index`: target scanning and caching.
    - `cn.varsa.pde.resolver.features`: feature enumeration.
    - `cn.varsa.pde.resolver.algo`: dependency resolver (Require-Bundle,
      Import-Package, Fragment-Host, re-exports).
    - `cn.varsa.pde.resolver.launch`: launcher models, assembler, renderers.
  Module boundaries
    - Core: pure Kotlin/JVM with `Path` and stream usage only.
    - IntelliJ plugin: IDE-specific adapters (module mapping, order entry
      updates, progress reporting).
    - Optional CLI: depends solely on the core module.
  Dependency graph
    - IntelliJ plugin → `pde-resolver`.
    - `pde-resolver-cli` (future) → `pde-resolver`.

Status and Roadmap
  Completed
    - Target platform scanning, caching, and feature discovery consolidated
      in core.
    - Resolver provides closure over Require-Bundle, Import-Package, and
      Fragment-Host with deterministic ordering and diagnostics.
    - Launcher models, assembler, and renderers extracted to core and wired
      into the plugin run configuration.
    - Plugin leverages lazy library creation, stable order entry updates,
      centralised target indexing, and user-facing notifications.
  Next
    - Publish CLI commands that surface unresolved bundles and emit launch
      artefacts via the shared renderers.
    - Reuse `ResolveResult` instances across plugin workflows to avoid
      redundant recomputation.
    - Guard target index reuse with proactive VFS refreshes when roots first
      appear.
  Open TODOs
    - Invalidate `PluginTargetIndexService` on target changes.
    - Clean up nested JAR extraction in the compile-only resolver.
    - Consider batching `ProjectLibraryIndexService` rebuilds.
    - Remove obsolete “Exclude Bundles” message keys.
    - Extend `PluginXmlIndex` to cover source-only `plugin.xml` entries.

Lessons Learned
  - Launch plans must include every target bundle plus workspace dev modules
    to match legacy `LaunchConfigGenerator` behaviour.
  - Honour bundle version ranges; selecting the highest version can break
    strict ranges (for example `[1.2.2,2.0.0)` for `javax.activation`).
  - Workspace bundles override target copies to prevent duplicates and keep
    dev modules authoritative.
  - Preserve legacy `config.ini` keys (`osgi.install.area`,
    `org.eclipse.equinox.simpleconfigurator.configUrl`, and friends) or the
    platform fails during bootstrap.
  - Ensure product and application bundles are present; otherwise the
    framework registry lacks the configured application.
  - Diff new `bundles.info` and `config.ini` against a known-good baseline to
    catch regressions quickly.
