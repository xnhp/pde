package cn.varsa.pde.resolver.algo

import cn.varsa.pde.resolver.index.TargetPlatformIndex
import cn.varsa.pde.resolver.index.ResolvedBundle as TargetResolvedBundle
import cn.varsa.pde.resolver.manifest.BundleManifest
import cn.varsa.pde.resolver.manifest.exportedPackageAndVersion
import cn.varsa.pde.resolver.manifest.fragmentHostAndVersionRange
import cn.varsa.pde.resolver.manifest.importedPackageAndVersion
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
  val outputDirectory: Path? = null
) {
  data class FragmentHost(val symbolicName: String, val versionRange: VersionRange?)
}

data class ResolveOptions(
  val whitelistPrefixes: Set<String> = emptySet(),
  val preferWorkspace: Boolean = true,
  val includeHostsForFragments: Boolean = true
)

enum class BundleOrigin { WORKSPACE, TARGET }

data class ResolvedBundle(
  val bsn: String,
  val version: Version,
  val path: Path,
  val origin: BundleOrigin,
  val classPathEntries: List<Path> = listOf(path),
  val sourceEntries: List<Path> = emptyList(),
  val fragmentHost: String? = null,
  val isHost: Boolean = false,
  val reexport: Boolean = false
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

    fun select(bsn: String, range: VersionRange?): Candidate? {
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
    val unresolved = LinkedHashSet<UnresolvedBundle>()

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

    fun addRequireWithClosure(bsn: String, range: VersionRange) {
      if (!selected.containsKey(bsn)) {
        val cand = select(bsn, range)
        if (cand != null) {
          selected.putIfAbsent(bsn, cand)
          val requiresAll = if (cand.origin == BundleOrigin.WORKSPACE) {
            cand.manifest.requiredBundleAndVersion(includeOptional = true) +
              cand.manifest.reexportRequiredBundleAndVersion(includeOptional = true)
          } else {
            val nav = target.requiresByBundle()[cand.bsn]?.get(cand.version) ?: emptyMap()
            nav + cand.manifest.reexportRequiredBundleAndVersion(includeOptional = true)
          }
          requiresAll.forEach { (childBsn, childRange) -> addRequireWithClosure(childBsn, childRange) }
        } else {
          unresolved.add(UnresolvedBundle(bsn, range, "require-bundle"))
        }
      }
    }

    val requires = LinkedHashMap(entry.manifest.requiredBundleAndVersion(includeOptional = true))
    fragmentHostCandidate?.manifest?.requiredBundleAndVersion(includeOptional = true)?.forEach { (bsn, range) ->
      requires.putIfAbsent(bsn, range)
    }
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

    val importedPkgs = imports.keys
    val tpProvidersByPkg: Map<String, java.util.NavigableMap<Version, PkgProvider>> = run {
      val map = HashMap<String, java.util.NavigableMap<Version, PkgProvider>>()
      val byPkg = target.exportedBundlesByPackageNav()
      importedPkgs.forEach { pkg ->
        val nav = byPkg[pkg] ?: return@forEach
        val providers = java.util.TreeMap<Version, PkgProvider>()
        nav.forEach { (ver, rb) ->
          val bsn = rb.manifest.bundleSymbolicName?.key ?: return@forEach
          providers[ver] = PkgProvider(
            bsn,
            ver,
            rb.location,
            rb.manifest,
            BundleOrigin.TARGET,
            computeTargetClassPathEntries(rb, target),
            computeTargetSourceEntries(rb, target)
          )
        }
        if (providers.isNotEmpty()) map[pkg] = providers
      }
      map
    }

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
          return Candidate(
            bsn = ws.bsn,
            version = ws.version,
            path = ws.path,
            manifest = ws.manifest,
            origin = BundleOrigin.WORKSPACE,
            classPathEntries = ws.classPathEntries,
            sourceEntries = ws.sourceEntries,
            fragmentHost = ws.manifest.fragmentHost?.key
          )
        }
      }

      val nav = tpProvidersByPkg[pkg] ?: return null
      val entry = nav.descendingMap().entries.firstOrNull { e ->
        val man = nav[e.key]?.manifest ?: return@firstOrNull false
        val ver = exportsOf(man)[pkg]
        ver != null && range.includes(ver)
      } ?: return null
      val provider = entry.value
      return Candidate(
        bsn = provider.bsn,
        version = provider.version,
        path = provider.path,
        manifest = provider.manifest,
        origin = provider.origin,
        classPathEntries = provider.classPathEntries,
        sourceEntries = provider.sourceEntries,
        fragmentHost = provider.manifest.fragmentHost?.key
      )
    }

    imports.forEach { (pkg, range) ->
      val provider = findProviderForPackage(pkg, range)
      if (provider != null && !selected.containsKey(provider.bsn)) {
        selected[provider.bsn] = provider
      } else if (provider == null) {
        unresolved.add(UnresolvedBundle(pkg, range, "import-package"))
      }
    }

    if (options.whitelistPrefixes.isNotEmpty()) {
      target.bundlesByBsn().keys.forEach { bsn ->
        if (!selected.containsKey(bsn) && options.whitelistPrefixes.any { bsn.startsWith(it) }) {
          val nav = target.bundlesByBsn()[bsn]
          val rb = nav?.lastEntry()?.value
          if (rb != null) {
            selected[bsn] = candidateFromTarget(rb)
          }
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
      isHost = c.isHost
    )

    val bundles = mutableListOf<ResolvedBundle>()
    val hostBsn = hostPair?.first
    if (hostBsn != null) selected[hostBsn]?.let { bundles.add(toResolved(it)) }
    selected.forEach { (bsn, cand) ->
      if (bsn != hostBsn) bundles.add(toResolved(cand))
    }

    val moduleDependencies = bundles
      .filter { it.isWorkspace }
      .map { it.bsn }
      .toMutableSet()
      .apply { remove(entrySymbolicName) }
      .toSet()

    val problems = unresolved.map { u ->
      val type = when (u.reason) {
        "fragmentHost" -> ResolveProblemType.FRAGMENT_HOST
        "import-package" -> ResolveProblemType.MISSING_PACKAGE
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
