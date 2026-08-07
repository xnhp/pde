package cn.varsa.pde.resolver.algo

import cn.varsa.pde.resolver.index.TargetPlatformIndex
import cn.varsa.pde.resolver.index.ResolvedBundle as TargetResolvedBundle
import cn.varsa.pde.resolver.manifest.BundleManifest
import cn.varsa.pde.resolver.manifest.exportedPackageAndVersion
import cn.varsa.pde.resolver.manifest.fragmentHostAndVersionRange
import cn.varsa.pde.resolver.manifest.importedPackageAndVersion
import cn.varsa.pde.resolver.manifest.isLazyActivated
import cn.varsa.pde.resolver.manifest.requiredBundleAndVersion
import cn.varsa.pde.resolver.manifest.reexportRequiredBundleAndVersion
import org.osgi.framework.Version
import org.osgi.framework.VersionRange
import java.nio.file.Files
import java.nio.file.Path

/** Description of a workspace bundle (typically an IDE module). */
data class WorkspaceBundleDescriptor(
  val path: Path,
  val manifest: BundleManifest,
  val classPathEntries: List<Path> = listOf(path.toAbsolutePath().normalize()),
  val sourceEntries: List<Path> = emptyList(),
  val fragmentHost: FragmentHost? = null,
  val sourceRoots: List<Path> = emptyList(),
  val resourceIncludes: List<String> = emptyList(),
  val resourceExcludes: List<String> = emptyList(),
  val compilerPrefs: Map<String, String> = emptyMap(),
  val executionEnvironment: String? = null,
  val outputDirectory: Path? = null,
  val outputDirectoryFromBuildProperties: Boolean = false
) {
  data class FragmentHost(val symbolicName: String, val versionRange: VersionRange?)
}

data class ResolveOptions(
  val whitelistPrefixes: Set<String> = emptySet(),
  val preferWorkspace: Boolean = true,
  val includeHostsForFragments: Boolean = true,
  /** Exact bundle versions to select when a BSN has multiple matching candidates. */
  val pinnedVersions: Map<String, Version> = emptyMap(),
  /** BSNs, optionally `bsn@version`, to add after normal resolution. */
  val extraBundles: List<String> = emptyList()
)

enum class BundleOrigin { WORKSPACE, TARGET }

/**
 * Packages provided by the JRE / OSGi system bundle (boot classpath), never exported by a regular
 * bundle. The indirect-import closure must skip these — otherwise it records hundreds of bogus
 * `import-package-indirect` unresolved entries for `javax.*`/`jdk.*`/etc.
 */
private val SYSTEM_PACKAGE_PREFIXES = listOf(
  "java.", "javax.", "jakarta.", "jdk.", "sun.", "com.sun.",
  "org.w3c.dom", "org.xml.sax", "org.ietf.jgss", "org.omg."
)

internal fun isSystemPackage(pkg: String): Boolean =
  SYSTEM_PACKAGE_PREFIXES.any { pkg == it.trimEnd('.') || pkg.startsWith(it) }

data class ResolvedBundle(
  val bsn: String,
  val version: Version,
  val path: Path,
  val origin: BundleOrigin,
  val classPathEntries: List<Path> = listOf(path),
  val sourceEntries: List<Path> = emptyList(),
  val fragmentHost: String? = null,
  val isHost: Boolean = false,
  val reexport: Boolean = false,
  /** Bundle declares `Bundle-ActivationPolicy: lazy` — must be armed (started) for DS/activator to run. */
  val lazyActivation: Boolean = false
) {
  val isWorkspace: Boolean get() = origin == BundleOrigin.WORKSPACE
}

data class UnresolvedBundle(
  val bsn: String,
  val range: VersionRange?,
  val reason: String
)

enum class ResolveProblemType { MISSING_BUNDLE, VERSION_OUT_OF_RANGE, MISSING_PACKAGE, OPTIONAL_IGNORED, FRAGMENT_HOST }

data class ResolveProblem(
  val type: ResolveProblemType,
  val symbol: String,
  val range: VersionRange? = null,
  val message: String
)

data class ResolveResult(
  val bundles: List<ResolvedBundle>,
  val imports: Map<String, VersionRange>,
  val requires: Map<String, VersionRange>,
  val unresolved: List<UnresolvedBundle> = emptyList(),
  val moduleDependencies: Set<String> = emptySet(),
  val problems: List<ResolveProblem> = emptyList()
)

object Resolver {
  fun resolve(
    target: TargetPlatformIndex,
    workspace: List<WorkspaceBundleDescriptor>,
    entry: WorkspaceBundleDescriptor,
    options: ResolveOptions = ResolveOptions()
  ): ResolveResult {
    val workspaceByBsn = workspace.groupBy { it.manifest.bundleSymbolicName?.key ?: "" }

    data class Candidate(
      val bsn: String,
      val version: Version,
      val path: Path,
      val manifest: BundleManifest,
      val origin: BundleOrigin,
      val classPathEntries: List<Path>,
      val sourceEntries: List<Path>,
      val fragmentHost: String? = null,
      val isHost: Boolean = false
    )

    fun candidateFromWorkspace(desc: WorkspaceBundleDescriptor, isHost: Boolean = false): Candidate {
      val bsn = desc.manifest.bundleSymbolicName?.key
        ?: error("Workspace bundle lacks Bundle-SymbolicName header at ${desc.path}")
      return Candidate(
        bsn = bsn,
        version = desc.manifest.bundleVersion,
        path = desc.path,
        manifest = desc.manifest,
        origin = BundleOrigin.WORKSPACE,
        classPathEntries = desc.classPathEntries.ifEmpty { listOf(desc.path) },
        sourceEntries = desc.sourceEntries,
        fragmentHost = desc.fragmentHost?.symbolicName,
        isHost = isHost
      )
    }

    fun candidateFromTarget(rb: TargetResolvedBundle, isHost: Boolean = false): Candidate {
      val bsn = rb.manifest.bundleSymbolicName?.key
        ?: error("Target bundle lacks Bundle-SymbolicName header at ${rb.location}")
      return Candidate(
        bsn = bsn,
        version = rb.manifest.bundleVersion,
        path = rb.location,
        manifest = rb.manifest,
        origin = BundleOrigin.TARGET,
        classPathEntries = computeTargetClassPathEntries(rb, target),
        sourceEntries = computeTargetSourceEntries(rb, target),
        fragmentHost = rb.manifest.fragmentHost?.key,
        isHost = isHost
      )
    }

    val unresolved = LinkedHashSet<UnresolvedBundle>()
    val directProblems = mutableListOf<ResolveProblem>()

    fun exactVersionRange(version: Version) =
      VersionRange(VersionRange.LEFT_CLOSED, version, version, VersionRange.RIGHT_CLOSED)

    fun selectExact(bsn: String, version: Version): Candidate? {
      if (options.preferWorkspace) {
        val ws = workspaceByBsn[bsn]
          ?.firstOrNull { it.manifest.bundleVersion == version }
        if (ws != null) return candidateFromWorkspace(ws)
      }

      return target.get(bsn, exactVersionRange(version))?.let { candidateFromTarget(it) }
    }

    val pinnedRangeWarnings = LinkedHashSet<String>()
    fun recordPinnedRangeWarning(bsn: String, requestedRange: VersionRange, pinnedVersion: Version) {
      val key = "$bsn|$requestedRange|$pinnedVersion"
      if (pinnedRangeWarnings.add(key)) {
        directProblems += ResolveProblem(
          type = ResolveProblemType.VERSION_OUT_OF_RANGE,
          symbol = bsn,
          range = requestedRange,
          message = "pinned version $pinnedVersion is outside the requested range; using the pin anyway"
        )
      }
    }

    fun select(bsn: String, range: VersionRange?): Candidate? {
      val pinnedVersion = options.pinnedVersions[bsn]
      if (pinnedVersion != null) {
        if (range != null && !range.includes(pinnedVersion)) {
          recordPinnedRangeWarning(bsn, range, pinnedVersion)
        }
        return selectExact(bsn, pinnedVersion)
      }

      if (options.preferWorkspace) {
        val ws = workspaceByBsn[bsn]
          ?.asSequence()
          ?.map { it to it.manifest.bundleVersion }
          ?.filter { (_, v) -> range == null || range.includes(v) }
          ?.maxByOrNull { it.second }
          ?.first
        if (ws != null) return candidateFromWorkspace(ws)
      }

      val targetBundle = target.get(bsn, range)
      return targetBundle?.let { candidateFromTarget(it) }
    }

    val selected = LinkedHashMap<String, Candidate>()
    var fragmentHostCandidate: Candidate? = null

    val manifestExportsCache = java.util.IdentityHashMap<BundleManifest, Map<String, Version>>()
    fun exportsOf(manifest: BundleManifest): Map<String, Version> =
      manifestExportsCache.getOrPut(manifest) { manifest.exportedPackageAndVersion() }

    val entrySymbolicName = entry.manifest.bundleSymbolicName?.key
      ?: error("Entry bundle lacks Bundle-SymbolicName header at ${entry.path}")
    selected[entrySymbolicName] = candidateFromWorkspace(entry)

    val hostPair = entry.fragmentHost?.let { it.symbolicName to it.versionRange }
      ?: entry.manifest.fragmentHostAndVersionRange()
    if (options.includeHostsForFragments && hostPair != null) {
      val (hostBsn, hostRange) = hostPair
      val host = select(hostBsn, hostRange)
      if (host != null) {
        val resolvedHost = host.copy(isHost = true)
        selected[hostBsn] = resolvedHost
        fragmentHostCandidate = resolvedHost
      } else {
        unresolved.add(UnresolvedBundle(hostBsn, hostRange, "fragmentHost"))
      }
    }

    // Expand a Require-Bundle closure. Selection is "first constraint wins", with selection split
    // from recursion (via `closureProcessed`) so a bundle pre-selected in phase 1 below still has
    // its own requires expanded.
    val closureProcessed = HashSet<String>()
    fun addRequireWithClosure(bsn: String, range: VersionRange) {
      if (!selected.containsKey(bsn)) {
        val cand = select(bsn, range)
        if (cand == null) {
          unresolved.add(UnresolvedBundle(bsn, range, "require-bundle"))
          return
        }
        selected[bsn] = cand
      }
      if (!closureProcessed.add(bsn)) return
      val cand = selected[bsn] ?: return
      val requiresAll = if (cand.origin == BundleOrigin.WORKSPACE) {
        cand.manifest.requiredBundleAndVersion(includeOptional = true) +
          cand.manifest.reexportRequiredBundleAndVersion(includeOptional = true)
      } else {
        val nav = target.requiresByBundle()[cand.bsn]?.get(cand.version) ?: emptyMap()
        nav + cand.manifest.reexportRequiredBundleAndVersion(includeOptional = true)
      }
      requiresAll.forEach { (childBsn, childRange) -> addRequireWithClosure(childBsn, childRange) }
    }

    val requires = LinkedHashMap(entry.manifest.requiredBundleAndVersion(includeOptional = true))
    fragmentHostCandidate?.manifest?.requiredBundleAndVersion(includeOptional = true)?.forEach { (bsn, range) ->
      requires.putIfAbsent(bsn, range)
    }
    // Phase 1: lock the entry's (and fragment host's) DIRECT Require-Bundle constraints at their own
    // ranges before expanding the transitive closure. When the target ships several versions of a
    // library, a transitive path that requires it at a wider range (e.g. another bundle wanting
    // Guava 33 while this one declares `[19,20)`) would otherwise grab the global-highest version
    // first, and this bundle would compile against a newer library than its declared range binds at
    // launch -> NoSuchMethodError. Equinox wires each bundle to its own range, so the compile
    // classpath must match.
    requires.forEach { (bsn, range) ->
      if (!selected.containsKey(bsn)) {
        select(bsn, range)?.let { selected[bsn] = it }
      }
    }
    // Phase 2: expand the full transitive closure (recurses even into phase-1-selected bundles).
    requires.forEach { (bsn, range) -> addRequireWithClosure(bsn, range) }

    val imports = LinkedHashMap(entry.manifest.importedPackageAndVersion())
    fragmentHostCandidate?.manifest?.importedPackageAndVersion()?.forEach { (pkg, range) ->
      imports.putIfAbsent(pkg, range)
    }

    data class PkgProvider(
      val bsn: String,
      val version: Version,
      val path: Path,
      val manifest: BundleManifest,
      val origin: BundleOrigin,
      val classPathEntries: List<Path>,
      val sourceEntries: List<Path>
    )

    val wsProvidersByPkg: Map<String, List<PkgProvider>> = run {
      val map = HashMap<String, MutableList<PkgProvider>>()
      workspace.forEach { desc ->
        val man = desc.manifest
        val bsn = man.bundleSymbolicName?.key ?: return@forEach
        val exp = exportsOf(man)
        exp.forEach { (pkg, _) ->
          map.computeIfAbsent(pkg) { mutableListOf() }
            .add(
              PkgProvider(
                bsn,
                man.bundleVersion,
                desc.path,
                man,
                BundleOrigin.WORKSPACE,
                desc.classPathEntries.ifEmpty { listOf(desc.path) },
                desc.sourceEntries
              )
            )
        }
      }
      map
    }

    fun providerToCandidate(provider: PkgProvider) = Candidate(
      bsn = provider.bsn,
      version = provider.version,
      path = provider.path,
      manifest = provider.manifest,
      origin = provider.origin,
      classPathEntries = provider.classPathEntries,
      sourceEntries = provider.sourceEntries,
      fragmentHost = provider.manifest.fragmentHost?.key
    )

    // If the provider's BSN is pinned, the pinned version replaces the candidate — but only if
    // it still exports the package within the requested range; otherwise the candidate is
    // rejected (a pin must not silently satisfy an import it cannot actually provide).
    fun applyPin(candidate: Candidate, pkg: String, range: VersionRange): Candidate? {
      val pinnedVersion = options.pinnedVersions[candidate.bsn] ?: return candidate
      val pinned = selectExact(candidate.bsn, pinnedVersion) ?: return null
      val exported = exportsOf(pinned.manifest)[pkg] ?: return null
      return if (range.includes(exported)) pinned else null
    }

    // Provider of an imported package: workspace exporters first, then target exporters ordered
    // by bundle version descending (symbolic name as deterministic tie-break). The target lookup
    // enumerates EVERY exporter of the package — unlike a version-keyed map, two bundles
    // exporting the same package at the same bundle version don't shadow each other; the first
    // whose export version satisfies the range (and survives pinning) wins.
    fun findProviderForPackage(pkg: String, range: VersionRange): Candidate? {
      if (options.preferWorkspace) {
        val ws = wsProvidersByPkg[pkg]
          ?.asSequence()
          ?.mapNotNull { provider ->
            val exported = exportsOf(provider.manifest)[pkg]
            if (exported != null && range.includes(exported)) provider else null
          }
          ?.maxByOrNull { it.version }
        if (ws != null) {
          val pinned = applyPin(providerToCandidate(ws), pkg, range)
          if (pinned != null) return pinned
        }
      }

      val providers = target.exportedBundlesByPackage()[pkg] ?: return null
      return providers.asSequence().mapNotNull { rb ->
        val exported = exportsOf(rb.manifest)[pkg] ?: return@mapNotNull null
        if (!range.includes(exported)) return@mapNotNull null
        applyPin(candidateFromTarget(rb), pkg, range)
      }.firstOrNull()
    }

    // Requirements-closure fixpoint, approximating Eclipse PDE's
    // DependencyManager.findRequirementsClosure: every selected bundle's Import-Package,
    // Require-Bundle, and Fragment-Host dependencies are followed until the selection is stable,
    // so e.g. junit-jupiter-api -> org.opentest4j (a package a closure member only Imports) lands
    // on the compile classpath instead of ecj reporting "indirectly referenced from required
    // type". Unlike Eclipse PDE there is no resolved Equinox wiring state to read exporters from,
    // so each package's provider is re-derived (workspace first, pins honored, then highest
    // in-range export). An import is satisfied only by its resolved provider: an unrelated
    // selected bundle exporting the same package does not mask it — if the provider is already
    // selected nothing is added, otherwise it joins the selection. Optional imports are followed
    // like Eclipse PDE's INCLUDE_OPTIONAL_DEPENDENCIES build closure; an unsatisfiable optional
    // import is skipped silently. Unsatisfiable JRE/system-package imports (java./jdk./...) are
    // also skipped silently — they come from the OSGi system bundle via boot delegation, so their
    // absence from the target index is expected — but a bundle-provided javax.* package (e.g.
    // javax.servlet) still resolves normally because providers are looked up before the check.
    run {
      val processed = HashSet<String>()
      while (true) {
        val pending = selected.keys.filter { it !in processed }
        if (pending.isEmpty()) break
        pending.forEach { bsn ->
          processed.add(bsn)
          val cand = selected[bsn] ?: return@forEach
          val manifest = cand.manifest

          // A fragment pulled into the closure needs its host on the classpath (the entry's own
          // host was already handled above, gated by includeHostsForFragments).
          if (bsn != entrySymbolicName) {
            manifest.fragmentHostAndVersionRange()?.let { (hostBsn, hostRange) ->
              if (!selected.containsKey(hostBsn)) {
                val host = select(hostBsn, hostRange)
                if (host != null) selected[hostBsn] = host
                else unresolved.add(UnresolvedBundle(hostBsn, hostRange, "fragmentHost"))
              }
            }
          }

          // Bundles that entered the selection via Import-Package still bring their own
          // Require-Bundle closure (idempotent for bundles the require phase already expanded).
          addRequireWithClosure(bsn, exactVersionRange(cand.version))

          val mandatoryImports = manifest.importedPackageAndVersion()
          manifest.importedPackageAndVersion(includeOptional = true).forEach forEachPkg@{ (pkg, range) ->
            val provider = findProviderForPackage(pkg, range)
            if (provider == null) {
              if (pkg in mandatoryImports && !isSystemPackage(pkg)) {
                val reason =
                  if (bsn == entrySymbolicName || bsn == hostPair?.first) "import-package"
                  else "import-package-indirect"
                unresolved.add(UnresolvedBundle(pkg, range, reason))
              }
              return@forEachPkg
            }
            // Only the resolved provider satisfies the import; if it is already selected
            // (possibly at another version — consistency first) there is nothing to add.
            if (!selected.containsKey(provider.bsn)) {
              selected[provider.bsn] = provider
            }
          }
        }
      }
    }

    if (options.whitelistPrefixes.isNotEmpty()) {
      target.bundlesByBsn().keys.forEach { bsn ->
        if (!selected.containsKey(bsn) && options.whitelistPrefixes.any { bsn.startsWith(it) }) {
          select(bsn, null)?.let { selected[bsn] = it }
        }
      }
    }

    fun toResolved(c: Candidate) = ResolvedBundle(
      bsn = c.bsn,
      version = c.version,
      path = c.path,
      origin = c.origin,
      classPathEntries = c.classPathEntries,
      sourceEntries = c.sourceEntries,
      fragmentHost = c.fragmentHost,
      isHost = c.isHost,
      lazyActivation = c.manifest.isLazyActivated()
    )

    val bundles = mutableListOf<ResolvedBundle>()
    val hostBsn = hostPair?.first
    if (hostBsn != null) selected[hostBsn]?.let { bundles.add(toResolved(it)) }
    selected.forEach { (bsn, cand) ->
      if (bsn != hostBsn) bundles.add(toResolved(cand))
    }

    options.extraBundles.forEach { spec ->
      val at = spec.indexOf('@')
      val bsn = (if (at >= 0) spec.substring(0, at) else spec).trim()
      if (bsn.isEmpty()) return@forEach
      val cand = if (at >= 0) {
        val version = runCatching { Version.parseVersion(spec.substring(at + 1).trim()) }.getOrNull()
        if (version == null) {
          unresolved.add(UnresolvedBundle(bsn, null, "extra-bundle"))
          return@forEach
        }
        workspaceByBsn[bsn]
          ?.firstOrNull { it.manifest.bundleVersion == version }
          ?.let { candidateFromWorkspace(it) }
          ?: target.get(bsn, exactVersionRange(version))
            ?.let { candidateFromTarget(it) }
      } else {
        select(bsn, null)
      }
      if (cand != null) bundles.add(toResolved(cand))
      else unresolved.add(UnresolvedBundle(bsn, null, "extra-bundle"))
    }

    val moduleDependencies = bundles
      .filter { it.isWorkspace }
      .map { it.bsn }
      .toMutableSet()
      .apply { remove(entrySymbolicName) }
      .toSet()

    val problems = directProblems + unresolved.map { u ->
      val type = when (u.reason) {
        "fragmentHost" -> ResolveProblemType.FRAGMENT_HOST
        "import-package", "import-package-indirect" -> ResolveProblemType.MISSING_PACKAGE
        else -> ResolveProblemType.MISSING_BUNDLE
      }
      ResolveProblem(type, u.bsn, u.range, u.reason)
    }

    return ResolveResult(
      bundles = bundles,
      imports = imports,
      requires = requires,
      unresolved = unresolved.toList(),
      moduleDependencies = moduleDependencies,
      problems = problems
    )
  }

  private fun computeTargetClassPathEntries(rb: TargetResolvedBundle, target: TargetPlatformIndex): List<Path> {
    val manifest = rb.manifest
    val base = rb.location.toAbsolutePath().normalize()
    val entries = mutableListOf(base)
    manifest.bundleClassPath?.keys
      ?.filter { it != "." }
      ?.forEach { entry ->
        if (rb.isDirectory) {
          val resolved = base.resolve(entry).normalize()
          if (Files.exists(resolved)) entries.add(resolved)
        }
        // For jar bundles we currently expose only the jar itself.
      }

    // Attach host fragments (e.g., platform-specific SWT) to the classpath.
    val hostBsn = manifest.bundleSymbolicName?.key
    val hostVersion = manifest.bundleVersion
    if (hostBsn != null) {
      target.bundlesByBsn().values.forEach { nav ->
        nav.values.forEach { frag ->
          val hostPair = frag.manifest.fragmentHostAndVersionRange() ?: return@forEach
          val (fragmentHostBsn, fragmentHostRange) = hostPair
          if (fragmentHostBsn == hostBsn && fragmentHostRange.includes(hostVersion)) {
            val fragBase = frag.location.toAbsolutePath().normalize()
            entries.add(fragBase)
            frag.manifest.bundleClassPath?.keys
              ?.filter { it != "." }
              ?.forEach { entry ->
                if (frag.isDirectory) {
                  val resolved = fragBase.resolve(entry).normalize()
                  if (Files.exists(resolved)) entries.add(resolved)
                }
                // For jar fragments the jar itself already covers Bundle-ClassPath contents.
              }
          }
        }
      }
    }

    return entries.distinct()
  }

  private fun computeTargetSourceEntries(rb: TargetResolvedBundle, target: TargetPlatformIndex): List<Path> {
    val bsn = rb.manifest.bundleSymbolicName?.key ?: return emptyList()
    val sourceBsn = "$bsn.source"
    val byBsn = target.bundlesByBsn()[sourceBsn]
    if (byBsn != null) {
      val exact = byBsn[rb.manifest.bundleVersion]
      if (exact != null) return listOf(exact.location.toAbsolutePath().normalize())
      val latest = byBsn.lastEntry()?.value
      if (latest != null) return listOf(latest.location.toAbsolutePath().normalize())
    }
    val fallback = target.get(sourceBsn)
    return fallback?.location?.let { listOf(it.toAbsolutePath().normalize()) } ?: emptyList()
  }
}
