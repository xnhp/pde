Resolver Extraction Summary

Scope
  - Extract PDE dependency resolution into a standalone library that both
    the IntelliJ plugin and future CLI tools can consume without behaviour
    changes.

Responsibility 1 – Parse Target Platform (Done)
  Highlights
    - Core `pde-resolver` module owns manifest models, target scanning, caching, and feature discovery.
    - IntelliJ bundle providers defer entirely to the shared index and cache.
  Tests
    - Existing manifest/index suites (e.g. `BundleManifestParseTest`, `TargetPlatformIndexTest`) continue to protect the contract.

Responsibility 2 – Resolve Workspace Bundles (Done)
  Highlights
    - Core resolver returns deterministic `ResolveResult` data with bundle order, module dependencies, and diagnostics.
    - Shared helpers (`ResolverContext`, `ResolverDiagnostics`, `ResolverOrdering`) power runtime, fragment, and launcher code paths.
    - Plugin runtime and fragment resolvers now consume the shared context, reuse cached `ResolveResult` instances via `ResolveSessionService`, and surface issues through the unified notifier.
  Launch integration
    - `LauncherPlanBuilder` reuses resolver outputs and reports problems with the same formatter as the IDE resolvers.

Responsibility 3 – Assemble Launch Configuration (In Progress)
  Core assets
    - Launcher models, assembler, and renderers live in `pde-resolver` and
      are covered by unit tests.
  Plugin integration
    - Run configuration code writes launch artefacts via core helpers after
      mapping project state through `ConfigService`.
  Status
    - Legacy `LaunchConfigGenerator` replaced by the shared launcher stack.
    - Core `LaunchEnvironment` + `LaunchPlanner` live in `pde-resolver`; the IDE now feeds ConfigService data into the core planner via a thin adapter.
  Next focus
    - Expose the same LaunchEnvironment surfaces to the CLI entry points and
      extend renderer coverage tests (config.ini, bundles.info, dev.properties).

Launch Logic Extraction Plan (Responsibility #3)
  1. Define core inputs — Done (Nov 17, 2025)
     - Introduced a typed `LaunchEnvironment` DTO in `pde-resolver` mirroring
       the fields currently surfaced by `ConfigService`.
  2. Move launch aggregation into core — Done (Nov 17, 2025)
     - `LaunchPlanner` now consumes `LaunchEnvironment`, returns `LauncherPlan`
       plus `LaunchContext`, and preserves workspace precedence/startup semantics.
  3. Expose rendering helpers
     - Extend existing renderers to accept simple data containers so they
       can be invoked by the IDE and a CLI without additional adapters.
  4. Adapt the IntelliJ plugin — Done (Nov 17, 2025)
     - LauncherPlanBuilder now maps ConfigService/ResolveSession data into
       `LaunchEnvironment` and delegates plan creation to the core module.
  5. Provide CLI-ready surfaces
     - Add a facade in `pde-resolver` that accepts filesystem inputs,
       constructs a `LaunchEnvironment`, and emits launch artefacts through
       injectable writers for future CLI consumption.
  6. Guard behaviour parity
     - Back the refactor with regression tests covering workspace override,
       startup levels, splash paths, framework selection, and generated
       `config.ini`, `bundles.info`, and `dev.properties` payloads.

Testing Prerequisites (target: early regression coverage)
  - ✅ Promote `LauncherPlanBuilder.build` into `pde-resolver` so tests can
    invoke launch planning without IntelliJ dependencies.
  - ✅ Introduce a serialisable `LaunchEnvironment` matching `ConfigService`
    inputs to feed deterministic target libraries, dev modules, and startup
    levels into tests.
  - Keep launcher renderers accepting DTOs only, allowing tests to call
    `ConfigIniRenderer`, `BundlesInfoRenderer`, and `DevPropertiesRenderer`
    directly.
  - Reuse resolver fixtures to capture representative `ResolveResult`
    scenarios and compare generated artefacts against golden strings,
    catching workspace precedence or configuration regressions early.


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
    - Target platform parsing/indexing handled in `pde-resolver`; plugin providers delegate to it.
    - Workspace resolver APIs shared across runtime, fragment, and launch flows with unified diagnostics/notifier support.
    - Launcher models/assembler/renderers operate from `pde-resolver`, with IDE adapters consuming them.
  Next
    - Expose CLI-friendly entry points backed by the new `LaunchEnvironment` / `LaunchPlanner` surfaces.
    - Formalise caching/reuse story for resolver sessions inside plugin services.
  Detailed Plan
    1. Extract resolver into core (`pde-resolver`) — Done
    2. Model workspace bundles independently — Done
    3. Publish CLI commands that surface unresolved bundles and emit launch artefacts via the shared renderers.
    4. Reuse `ResolveResult` instances across plugin workflows to avoid redundant recomputation. — Done (cached results now shared via `ResolveSessionService` and consumed by the launcher plan builder)
    5. Guard target index reuse with proactive VFS refreshes when roots first appear.
  Open TODOs
    - Add resolver unit tests covering: (completed November 17, 2025)
      - ✅ Fragment bundles inherit host dependencies (Require-Bundle, Import-Package, re-export).
      - ✅ Require-Bundle version range selection (workspace, target, and tie-breaking coverage).
      - ✅ Import-Package resolution (target bundles vs project libraries fallback).
      - ✅ Re-export chains (A re-exports B re-exports C).
      - ✅ Workspace vs target precedence for identical BSN/version.
      - ✅ Optional Require-Bundle / Import-Package handling.
      - ✅ Deterministic selection when multiple satisfying bundles exist.
      - ✅ Bundle-ClassPath entries (embedded JARs).
      - ✅ Fragment host selection when multiple host versions available.
      - ✅ Circular Require-Bundle relationships (no infinite loops).
    - Invalidate `PluginTargetIndexService` on target changes.
    - Clean up nested JAR extraction in the compile-only resolver.
    - Consider batching `ProjectLibraryIndexService` rebuilds.
    - Remove obsolete “Exclude Bundles” message keys.
    - Extend `PluginXmlIndex` to cover source-only `plugin.xml` entries.
    - Share bundle-selection logic between module resolver and launcher
      plan builder (reuse resolver version picks when generating
      launch plans).
    - Reintroduce launch preflight check that validates compiler outputs
      live under each bundle module (e.g. `<module>/out/production`,
      `<module>/out/tests`) instead of the project root.

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
