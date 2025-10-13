Resolver extraction summary

Scope
  - Extract PDE dependency resolution into a standalone library that both
    the IntelliJ plugin and a CLI/tool can consume.

Responsibilities
  1) Parse target platform profile
     - Build an index of bundles from target roots; discover features.
     - Implemented in core
       - `pde-resolver` module:
         - `cn.varsa.pde.resolver.manifest.BundleManifest` (shared model)
         - `cn.varsa.pde.resolver.support.*` (string/version helpers)
         - `cn.varsa.pde.resolver.index.TargetPlatformIndex` (scans Eclipse SDK
           roots, target definition profile dirs, plain dirs/files; BSN→version
           lookups)
         - `cn.varsa.pde.resolver.index.ProfileUtils` (`findProfileFile`,
           `getBundlePoolPath`, `mapProfileFile`)
         - `cn.varsa.pde.resolver.features.FeatureScanner` (features from SDK
           `features/`, P2 `pool/features`, Target Definition profiles)
         - Caching: `cn.varsa.pde.resolver.index.TargetPlatformCache`
           - Tree fingerprint of candidates (path:size:mtime)
           - Persisted snapshot (Properties) with schema and manifests
           - `buildWithCache(roots, cacheFile?)` uses cache by default
     - Plugin integration
       - Providers now use core scanners:
         - `DirectoryBundleProvider`, `EclipseSDKBundleProvider`,
           `EclipseP2BundleProvider`, `TargetDefinitionBundleProvider`
           call `TargetPlatformIndex` for bundles and `FeatureScanner` for
           features.
       - Removed obsolete `ProfileUtils` from plugin.
     - Tests (core)
       - `BundleManifestParseTest` — manifest headers parsing.
       - `ProfileUtilsTest` — profile emits expected artifacts.
       - `TargetPlatformIndexTest` — directory scanning/lookup.
       - `TargetPlatformIndexProfileTest` — profile + bundle pool indexing.
       - `FeatureScanner*Test` — feature enumeration from SDK/P2/profile.

  2) Resolve workspace bundles against target + workspace
     - Given workspace bundle descriptors and a target index, resolve
       Require-Bundle, Import-Package, Fragment-Host, and re-exports.
     - Implemented in core
       - `pde-resolver` module:
         - `cn.varsa.pde.resolver.algo.Resolver`
           - Input: `TargetPlatformIndex`, `List<WorkspaceBundleDescriptor>`,
             entry `WorkspaceBundleDescriptor`, and `ResolveOptions`.
           - Output: `ResolveResult` with:
             - `bundles`: ordered selected bundles (workspace + target),
               carrying `bsn`, `version`, `path`, `isWorkspace`, `isHost`.
             - `requires` and `imports` maps for diagnostics.
             - `unresolved`: list of missing fragment hosts or required
               bundles (reported as `UnresolvedBundle`).
         - Robust manifest parsing (`BundleManifest`, `Parameters`) with
           trimming for folded/multi-line headers.
         - Cached index usage via `TargetPlatformCache`.
     - Plugin orchestration
       - `src/main/kotlin/cn/varsa/idea/pde/partial/plugin/resolver/PdeModuleRuntimeLibraryResolver.kt`
         - Builds workspace descriptors, calls core `Resolver`.
         - Lazily creates project libraries per selected target bundle on
           EDT; mirrors workspace host module deps; orders entries.
         - Shows notifications for unresolved bundles (`PdeNotifier`).
       - `src/main/kotlin/cn/varsa/idea/pde/partial/plugin/resolver/PdeModuleFragmentLibraryResolver.kt`
         - Adds module dependency to host; orders fragment before host;
           uses core resolution to derive dependency order.
       - `src/main/kotlin/cn/varsa/idea/pde/partial/plugin/support/BundleManifestExt.kt`
         - Thin helpers around core `BundleManifest` for IDE use.

  3) Assemble config for launching RCP (core + IDE adapter done)
     - Core API (new in `pde-resolver`)
       - Model: `LauncherOptions`, `BundleStartSpec`, `LaunchContext`, `LauncherPlan`.
       - Assembly: `LauncherAssembler.from(ResolveResult, LaunchContext, LauncherOptions)`
         builds a bundle plan with start levels, auto-start flags, framework bundle.
       - Rendering: `ConfigIniRenderer`, `BundlesInfoRenderer`, `DevPropertiesRenderer`.
       - Unit tests cover assembly and rendering (see `LauncherAssemblerTest`).
     - IDE integration
       - Adapter `LauncherPlanBuilder` (plugin) maps `ConfigService`
         (libraries, dev modules, startup levels) to `LaunchContext` and core plan.
       - Run configuration now renders `config.ini`, `bundles.info`,
         `dev.properties` using core renderers; legacy `LaunchConfigGenerator`
         has been removed.
     - CLI adapter (future)
       - Create `LaunchContext` by scanning workspace + target, call core
         assembler/renderers, write files or JSON.

Proposed extraction
  - New library module
    - Landed: TargetPlatformIndex, TargetPlatformCache, FeatureScanner,
      shared `BundleManifest` model + helpers, `WorkspaceBundleDescriptor`,
      and core `Resolver` with unresolved reporting.
    - Plugin adapters
      - Map IntelliJ modules to `WorkspaceBundleDescriptor` and provide paths.
      - Maintain IDE library index/whitelist logic; call into library resolver
      for selection; then apply results to module order entries.

File signals and APIs to extract
  - Resolution orchestration
    - `src/main/kotlin/cn/varsa/idea/pde/partial/plugin/resolver/PdeModuleRuntimeLibraryResolver.kt:1`
  - Target indexing and lookups
    - Moved to core: `pde-resolver/src/main/kotlin/cn/varsa/pde/resolver/index/*`
      (including `TargetPlatformCache`)
  - Manifest utilities
    - `src/main/kotlin/cn/varsa/idea/pde/partial/plugin/support/BundleManifestExt.kt:1`
      (extensions targeting core `BundleManifest`)
  - Manifest caching/parsing
    - `src/main/kotlin/cn/varsa/idea/pde/partial/plugin/cache/BundleManifestCacheService.kt:1`
  - Bundle abstraction
    - `src/main/kotlin/cn/varsa/idea/pde/partial/plugin/domain/BundleDefinition.kt:1`

Seams (IDE-specific vs. pure)
  - IDE-specific
    - Module/order entry updates, library table interactions, progress
      services, and indices (`ProjectLibraryIndexService`, `PreferenceService`).
  - Pure
    - Manifest parsing (moved to core), target scanning (moved to core),
      future: dependency resolution, selection of BSN→version, and launch
      config assembly.

Public API (core, current + planned)
  - Target platform
    - Current: `TargetPlatformIndex.build(rootPaths: List<Path>)`
      - Scans Eclipse SDK roots, profile dirs, plain dirs/files for bundles.
      - Lookups: `get(bsn, range?)`, `bundlesByBsn()`.
    - Current: `TargetPlatformCache.buildWithCache(roots, cacheFile?)`
      - Computes fingerprint; loads cached snapshot if it matches;
        otherwise scans and persists snapshot.
    - Current: `FeatureScanner`
      - `scanEclipseSdkFeatures`, `scanTargetDefinitionFeatures`, `scanP2Features`.
    - Planned: richer data views (exports, fragments, re‑exports).
  - Workspace bundles
    - Current: `WorkspaceBundleDescriptor(path: Path, manifest: BundleManifest)`.
  - Resolution
    - Current: `Resolver.resolve(target, workspace, entry, options)`
      - Options: `whitelistPrefixes`, `preferWorkspace`,
        `includeHostsForFragments`.
      - Result: `ResolveResult { bundles, imports, requires, unresolved }`.
  - Launch config assembler
    - Planned: `LauncherConfig.from(result: ResolveResult, opts: LaunchOptions)`
      - Produces:
        - `frameworkProps: Map<String, String>` (e.g., osgi.* keys),
        - `bundlesList: List<BundleStartSpec>` (location, start level,
          auto-start),
        - optional `configIni: String` representation.

Migration checklist
  - Move or mirror pure code
    - Manifest header parsing and utilities from
      `.../support/BundleManifestExt.kt`.
    - Re-export closure algorithm shaped after
      `BundleManagementService.fillDependencies` but without IDE types.
    - Resolution ordering inspired by
      `bundleRequiredOrFromReExportOrderedList`.
  - Leave adapters in plugin
    - Module ↔ WorkspaceBundleDescriptor mapping, library table updates,
      ProjectLibraryIndex/whitelist use, progress handling.
  - Replace usages
    - `PdeModuleRuntimeLibraryResolver` to call `Resolver` and
      `LauncherConfig` for selection and layout, then apply to order
      entries.
  - Testing
    - Unit tests for: parsing, re-export closure, version range matching,
      import-package provider selection, fragment-host inheritance,
      deterministic selection by BSN/version.

Package layout
  - Core module `pde-resolver`
    - `cn.varsa.pde.resolver.manifest`: manifest model/adapters and header parsing.
    - `cn.varsa.pde.resolver.support`: string/version helpers.
    - `cn.varsa.pde.resolver.index`: `TargetPlatformIndex`, profile helpers,
      `TargetPlatformCache`.
    - `cn.varsa.pde.resolver.features`: `FeatureScanner`.
    - Planned:
      - `cn.varsa.pde.resolver.algo`: Resolver for Require‑Bundle,
        Import‑Package, Fragment‑Host; re‑export closure.
      - `cn.varsa.pde.resolver.launch`: `LauncherConfig`, `LaunchOptions`,
        `BundleStartSpec`; config.ini/framework props renderers.

  - CLI module `pde-resolver-cli` (optional)
    - `cn.varsa.pde.resolver.cli`: CLI entry; outputs classpath, config.ini,
      or JSON.

Module boundaries
  - Core: pure Kotlin/JVM with `Path`/streams only.
  - IntelliJ plugin: contains all IDE-specific adapters (module → descriptor
    mapping, applying results, ordering) and depends directly on core.
  - CLI: core-only, no IDE dependency.

Dependency graph
  - `pde-resolver-cli` -> `pde-resolver`
  - IntelliJ plugin -> `pde-resolver`

Note: A separate IntelliJ adapter module is not required for this project.
Keep IDE integration within the plugin unless reuse across multiple plugins
is anticipated.

Progress/Status
  - Done
    - Core
      - Resolver implemented: Require‑Bundle closure (incl. re‑exports),
        Import‑Package provider selection, optional Fragment‑Host inclusion.
      - `ResolveResult.unresolved` reports missing fragment hosts and
        required bundles (for IDE and CLI diagnostics).
      - Parameters parser trims item names and attributes (fixes folded
        headers like “, org.knime.base”).
      - TargetPlatformIndex + Cache used across plugin and CLI.
      - Unit tests: parsing, profile/index, features; trimming of
        Require‑Bundle; transitive resolution; unresolved reporting;
        launcher assembly/renderers.
    - Core launcher
      - Added launcher models, assembler, renderers, and tests.
      - Exported package & require-bundle indices cached (and persisted) for
        faster resolves.
    - Plugin
      - Runtime resolver and fragment resolver use core Resolver.
      - Project libraries created lazily on demand (EDT), with caching via
        `ProjectLibraryIndexService` when available.
      - Target index access centralized via `PluginTargetIndexService`.
      - Order-entry fixes: stable insertion indices, fragment-before-host.
      - Robust preResolve cleanup to avoid NPEs/bridge mismatches.
      - Unresolved bundles surfaced via warning notifications.
      - Removed BundleManagementService and “Exclude Bundles” UI/logic.
      - Removed unfinished ManifestBuildGuard feature and tests.
      - Run configuration builds launch files via core launcher; `LaunchConfigGenerator`
        deleted.
    - Performance
      - Avoids full VFS refresh; reuses cached target index; minimizes
        duplicate resolver invocations; lazy library creation.
  - Next
    - Expose a CLI subcommand to print unresolved bundles and selection.
    - CLI command to emit launch artifacts using core assembler/renderers.
    - Improve postResolve to reuse earlier ResolveResult instead of
      re-running (partially done via ResolveSessionService).
    - Add guarded VFS refresh in extension-point scanning when roots are
      first seen.

Open TODOs
  - Invalidate/rebuild PluginTargetIndexService on target changes.
  - Reuse resolve results in postResolve to avoid recomputation.
  - Synchronize nested jar extraction in compile-only resolver; cleanup.
  - Optionally trigger ProjectLibraryIndexService rebuild after bulk
    library creation (currently done per resolve; consider batching project-wide).
  - Remove unused message keys related to deprecated “Exclude Bundles”.
  - Ensure PluginXmlIndex covers source-only plugin.xml if needed.

Lessons learned / pitfalls
  - Bundles for launch must be enumerated the same way as the legacy
    LaunchConfigGenerator: include *every* target bundle plus workspace dev
    modules. Resolver-only output can miss optional/indirect dependencies
    (e.g., BIRT’s Apache Batik stack).
  - Respect bundle version ranges. Selecting the highest available version
    (e.g., `javax.activation` 2.x) breaks bundles that depend on
    `[1.2.2,2.0.0)`; planner must allow lower versions when required.
  - Workspace bundles need to override target copies in the final plan to
    avoid duplicates and ensure dev modules take precedence.
  - Carry forward legacy `config.ini` entries (`osgi.install.area`,
    `osgi.bundles`, simpleconfigurator configUrl, etc.); missing keys prevent
    the platform from bootstrapping.
  - Product/application bundles must be present in the plan; otherwise the
    framework registry won’t contain the configured application even if the
    target bundle exists.
  - Always diff new `bundles.info`/`config.ini` against a known-working
    version after planner changes to catch regressions quickly.

Detailed plan for Responsibility #3 (core launcher extraction)
  1. Stabilize current behaviour (parity with LaunchConfigGenerator)
     - ✅ LauncherPlanBuilder restored to legacy behaviour; ensure all target
       + workspace bundles are enumerated while preserving workspace override.
     - ✅ Config.ini writer carries all required legacy keys; verify path
       mapping (install/instance/config directories, configurator URL).
     - ✅ Document pitfalls: version ranges, product availability, diffing.
  2. Codify the plan assembly contract
     - Define explicit requirements in documentation/code comments: inclusion
       of all target bundles, preference for workspace, version range respect,
       support for startup levels/auto-start.
     - Add unit tests covering:
       * All bundles from target are present.
       * Workspace bundle overrides target.
       * Version range case (activation 1.x).
       * Optional dependencies (BIRT/Batik) remain present.
  3. Move shared launcher assembly into core (pde-resolver)
     - Create a “legacy mode” launcher assembler mirroring
       LaunchConfigGenerator logic (target bundles + workspace overlays).
     - Provide API to produce bundles.info/config.ini/dev.properties from
       ConfigService-like inputs (target libraries, dev modules, startup map).
     - Ensure core code exports minimal DTOs for product, options, startup.
  4. Refactor IDE run configuration to call core API
     - Replace plugin-side plan builder with the new core assembler.
     - Keep file writing (config.ini/dev/bundles.info) in plugin but delegate
       bundle enumeration and property assembly to core.
     - Maintain run configuration UI; feed `LauncherOptions` + context into
       core assembler.
  5. Introduce CLI client (optional but planned)
     - Create CLI command using core launcher API: load target/workspace, run
       assembler, emit config files or JSON descriptors.
     - Shared code ensures CLI and IDE generate identical artefacts.
  6. Hardening & diagnostics
     - Add logging/telemetry for plan size, missing bundles, version mismatches.
     - Provide optional “diff against baseline bundles.info” utility for quick
       regression checks.
     - Expose helper to run resolver diagnostics (unresolved bundles) before
       writing launch files; surface actionable info to users.

Launcher extraction plan (detailed)
  - Phase 1: Define core models and assembly entry
    - ✅ Done: core models/assembler/renderers with tests.
  - Phase 2: Add renderers
    - ✅ Done: renderers + unit tests (string assertions; add golden tests if desired).
  - Phase 3: IDE migration
    - ✅ Done: run configuration uses `LauncherPlanBuilder` + core renderers.
  - Phase 4: CLI adapter (optional)
    - Add simple CLI command to resolve and emit launch files or JSON.
    - Reuse the same core APIs; no IDE dependencies.
  - Phase 5: Performance & polish
    - Cache framework/extension candidate lookups in the target index.
    - Validate inputs (missing framework, invalid start levels) and report
      via launcher-specific diagnostics.
