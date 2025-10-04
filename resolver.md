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
     - Given workspace bundle paths/descriptors and a target index, resolve
       Require-Bundle, Import-Package, Fragment-Host, and re-exports.
     - Current code
       - `src/main/kotlin/cn/varsa/idea/pde/partial/plugin/resolver/PdeModuleRuntimeLibraryResolver.kt:1`
         - Core resolution orchestration for a module; selects libraries by
           BSN/version; handles fragments and host inheritance; integrates
           with IDE order entries and library index.
       - `src/main/kotlin/cn/varsa/idea/pde/partial/plugin/resolver/PdeModuleFragmentLibraryResolver.kt:1`
         - Fragment-specific resolution logic and host linkage.
       - `src/main/kotlin/cn/varsa/idea/pde/partial/plugin/support/BundleManifestExt.kt:1`
         - Helpers to compute: `requiredBundleAndVersion`, `importedPackageAndVersion`,
           `reexportRequiredBundleAndVersion`, `fragmentHostAndVersionRange`,
           and ordered resolution lists.
       - `src/main/kotlin/cn/varsa/idea/pde/partial/plugin/config/BundleManagementService.kt:1`
         - Provides bundle lookup by BSN/version and re-export closure used by
           the resolver.

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
    - In progress. Landed: TargetPlatformIndex, FeatureScanner, shared
      `BundleManifest` model + helpers.
    - Planned: `WorkspaceBundleDescriptor`, Resolver
      (Require‑Bundle/Import‑Package/Fragment), and LauncherConfig assembler.
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
    - Planned: `WorkspaceBundleDescriptor(path: Path, manifest: BundleManifest)`.
  - Resolution
    - Planned: `Resolver.resolve(
         target: TargetPlatformIndex,
         workspace: List<WorkspaceBundleDescriptor>,
         entry: WorkspaceBundleDescriptor,
         options: ResolveOptions
       ): ResolveResult`
      - Input options: optional whitelist, preferWorkspace flag, handle
        re-exports, include hosts for fragments.
      - Output `ResolveResult`
        - `bundles: List<ResolvedBundle>` where `ResolvedBundle` carries
          `bsn`, `version`, `path`, `isWorkspace`, `isHost`.
        - `imports: Map<String, VersionRange>` and
          `requires: Map<String, VersionRange>` for diagnostics.
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
    - Introduced `pde-resolver` core module and wired plugin dependency.
    - Implemented `TargetPlatformIndex`, profile helpers, and `FeatureScanner`.
    - Consolidated manifest model to core; removed plugin duplicate.
    - Updated bundle providers to consume core; removed plugin `ProfileUtils`.
    - Added core unit tests for manifest parsing, profile mapping, index,
      and feature scanning.
    - Build green with IntelliJ Gradle plugin 2.9.0.
    - Added tree‑fingerprint cache with persisted snapshot in core;
      `TargetPlatformCache.buildWithCache` entry point.
    - Providers now use cached build by default.
    - Added cache hit/miss test.
  - Next
    - Extract pure Resolver (Require‑Bundle/Import‑Package/Fragment‑Host).
    - Add resolver unit tests (version ranges, re‑export closure, fragments).
    - Implement LauncherConfig assembler and tests.
