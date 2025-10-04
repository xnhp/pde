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

  3) Assemble config for launching RCP
     - Convert resolved bundles to launch artifacts (e.g., bundles list with
       start levels, framework/system properties, optional `config.ini`).
     - Current code
       - No single assembler; parts live inside
         `PdeModuleRuntimeLibraryResolver` and order-entry arrangement in
         `postResolve` for runtime classpath. Launch-specific file emission
         does not exist yet; would be a new library component translating the
         resolved graph into config structures.

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
        Require‑Bundle; transitive resolution; unresolved reporting.
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
    - Performance
      - Avoids full VFS refresh; reuses cached target index; minimizes
        duplicate resolver invocations; lazy library creation.
  - Next
    - Expose a CLI subcommand to print unresolved bundles and selection.
    - LauncherConfig assembler and tests.
    - Improve postResolve to reuse earlier ResolveResult instead of
      re-running.
    - Add guarded VFS refresh in extension-point scanning when roots are
      first seen.

Open TODOs
  - Invalidate/rebuild PluginTargetIndexService on target changes.
  - Reuse resolve results in postResolve to avoid recomputation.
  - Synchronize nested jar extraction in compile-only resolver; cleanup.
  - Optionally trigger ProjectLibraryIndexService rebuild after bulk
    library creation.
  - Remove unused message keys related to deprecated “Exclude Bundles”.
  - Ensure PluginXmlIndex covers source-only plugin.xml if needed.
