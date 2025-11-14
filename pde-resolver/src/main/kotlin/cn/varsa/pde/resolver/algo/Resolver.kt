package cn.varsa.pde.resolver.algo

import cn.varsa.pde.resolver.index.TargetPlatformIndex
import cn.varsa.pde.resolver.manifest.BundleManifest
import cn.varsa.pde.resolver.manifest.exportedPackageAndVersion
import cn.varsa.pde.resolver.manifest.fragmentHostAndVersionRange
import cn.varsa.pde.resolver.manifest.importedPackageAndVersion
import cn.varsa.pde.resolver.manifest.requiredBundleAndVersion
import cn.varsa.pde.resolver.manifest.reexportRequiredBundleAndVersion
import org.osgi.framework.Version
import org.osgi.framework.VersionRange
import java.nio.file.Path

data class WorkspaceBundleDescriptor(
  val path: Path,
  val manifest: BundleManifest,
  val classPathEntries: List<Path> = listOf(path.toAbsolutePath().normalize()),
  val sourceEntries: List<Path> = emptyList(),
  val fragmentHost: FragmentHost? = null,
) {
  data class FragmentHost(val symbolicName: String, val versionRange: VersionRange?)
}

data class ResolveOptions(
  val whitelistPrefixes: Set<String> = emptySet(),
  val preferWorkspace: Boolean = true,
  val includeHostsForFragments: Boolean = true
)

data class ResolvedBundle(
  val bsn: String,
  val version: Version,
  val path: Path,
  val isWorkspace: Boolean,
  val isHost: Boolean
)

data class ResolveResult(
  val bundles: List<ResolvedBundle>,
  val imports: Map<String, VersionRange>,
  val requires: Map<String, VersionRange>,
  val unresolved: List<UnresolvedBundle> = emptyList()
)

data class UnresolvedBundle(
  val bsn: String,
  val range: VersionRange?,
  val reason: String
)

object Resolver {
  fun resolve(
    target: TargetPlatformIndex,
    workspace: List<WorkspaceBundleDescriptor>,
    entry: WorkspaceBundleDescriptor,
    options: ResolveOptions = ResolveOptions()
  ): ResolveResult {
    val workspaceByBsn: Map<String, List<WorkspaceBundleDescriptor>> =
      workspace.groupBy { it.manifest.bundleSymbolicName?.key ?: "" }

    data class Candidate(
      val bsn: String,
      val version: Version,
      val path: Path,
      val manifest: BundleManifest,
      val isWorkspace: Boolean,
      val isHost: Boolean = false
    )

    fun select(bsn: String, range: VersionRange?): Candidate? {
      if (options.preferWorkspace) {
        val ws = workspaceByBsn[bsn]
          ?.asSequence()
          ?.map { it to it.manifest.bundleVersion }
          ?.filter { (_, v) -> range == null || range.includes(v) }
          ?.maxByOrNull { it.second }
          ?.first
        if (ws != null) {
          return Candidate(
            bsn = bsn,
            version = ws.manifest.bundleVersion,
            path = ws.path,
            manifest = ws.manifest,
            isWorkspace = true
          )
        }
      }

      val t = target.get(bsn, range)
      return t?.let { rb ->
        Candidate(
          bsn = bsn,
          version = rb.manifest.bundleVersion,
          path = rb.location,
          manifest = rb.manifest,
          isWorkspace = false
        )
      }
    }

    val selected = LinkedHashMap<String, Candidate>()
    val unresolved = LinkedHashSet<UnresolvedBundle>()

    // Caches to avoid recomputing manifest exports repeatedly
    val manifestExportsCache = java.util.IdentityHashMap<BundleManifest, Map<String, Version>>()
    fun exportsOf(manifest: BundleManifest): Map<String, Version> =
      manifestExportsCache.getOrPut(manifest) { manifest.exportedPackageAndVersion() }

    // If entry is a fragment, include its host first (optional)
    // Always register the entry bundle (workspace preferred)
    entry.manifest.bundleSymbolicName?.key?.let { entryBsn ->
      val entryCandidate = Candidate(
        bsn = entryBsn,
        version = entry.manifest.bundleVersion,
        path = entry.path,
        manifest = entry.manifest,
        isWorkspace = true
      )
      selected.putIfAbsent(entryBsn, entryCandidate)
    }

    val hostPair = entry.manifest.fragmentHostAndVersionRange()
    if (options.includeHostsForFragments && hostPair != null) {
      val (hostBsn, hostRange) = hostPair
      val host = select(hostBsn, hostRange)
      if (host != null) {
        selected.putIfAbsent(hostBsn, host.copy(isHost = true))
      } else {
        unresolved.add(UnresolvedBundle(hostBsn, hostRange, "fragmentHost"))
      }
    }

    fun addRequireWithClosure(bsn: String, range: VersionRange) {
      if (!selected.containsKey(bsn)) {
        val cand = select(bsn, range)
        if (cand != null) {
          selected.putIfAbsent(bsn, cand)
          // Traverse full Require-Bundle closure (include re-exports implicitly)
          val requiresAll = if (cand.isWorkspace) cand.manifest.requiredBundleAndVersion() else
            target.requiresByBundle()[cand.bsn]?.get(cand.version) ?: emptyMap()
          for ((rb, rr) in requiresAll) addRequireWithClosure(rb, rr)
        } else {
          unresolved.add(UnresolvedBundle(bsn, range, "require-bundle"))
        }
      }
    }

    val requires = entry.manifest.requiredBundleAndVersion()
    requires.forEach { (bsn, range) -> addRequireWithClosure(bsn, range) }

    // Import-Package resolution: pick providers for requested packages
    val imports = entry.manifest.importedPackageAndVersion()

    // Precompute workspace export providers for faster lookups per package
    data class PkgProvider(val bsn: String, val version: Version, val path: Path, val manifest: BundleManifest, val isWs: Boolean)
    val wsProvidersByPkg: Map<String, List<PkgProvider>> = run {
      val map = HashMap<String, MutableList<PkgProvider>>()
      workspace.forEach { desc ->
        val man = desc.manifest
        val bsn = man.bundleSymbolicName?.key ?: return@forEach
        val exp = exportsOf(man)
        exp.forEach { (pkg, _) ->
          map.computeIfAbsent(pkg) { mutableListOf() }
            .add(PkgProvider(bsn, man.bundleVersion, desc.path, man, true))
        }
      }
      map
    }

    // Precompute target export providers only for imported packages using the index
    val importedPkgs = imports.keys.toSet()
    val tpProvidersByPkg: Map<String, java.util.NavigableMap<Version, PkgProvider>> = run {
      val map = HashMap<String, java.util.NavigableMap<Version, PkgProvider>>()
      val byPkg = target.exportedBundlesByPackageNav()
      importedPkgs.forEach { pkg ->
        val nav = byPkg[pkg] ?: return@forEach
        val providers = java.util.TreeMap<Version, PkgProvider>()
        nav.forEach { (ver, rb) ->
          val bsn = rb.manifest.bundleSymbolicName?.key ?: return@forEach
          providers[ver] = PkgProvider(bsn, ver, rb.location, rb.manifest, false)
        }
        if (providers.isNotEmpty()) map[pkg] = providers
      }
      map
    }

    fun findProviderForPackage(pkg: String, range: VersionRange): Candidate? {
      if (options.preferWorkspace) {
        val ws = wsProvidersByPkg[pkg]
          ?.asSequence()
          ?.filter { range.includes(exportsOf(it.manifest)[pkg]) }
          ?.maxByOrNull { it.version }
        if (ws != null) return Candidate(ws.bsn, ws.version, ws.path, ws.manifest, true)
      }

      val nav = tpProvidersByPkg[pkg] ?: return null
      val desc = nav.descendingMap()
      val entry = desc.entries.firstOrNull { e ->
        val man = nav[e.key]?.manifest ?: return@firstOrNull false
        val ver = exportsOf(man)[pkg]
        ver != null && range.includes(ver)
      } ?: return null
      val p = entry.value
      return Candidate(p.bsn, p.version, p.path, p.manifest, false)
    }

    imports.forEach { (pkg, range) ->
      val provider = findProviderForPackage(pkg, range)
      if (provider != null && !selected.containsKey(provider.bsn)) {
        selected[provider.bsn] = provider
      }
    }

    if (options.whitelistPrefixes.isNotEmpty()) {
      val keys = target.bundlesByBsn().keys
      // Single pass over BSNs to match prefixes
      keys.forEach { bsn ->
        if (!selected.containsKey(bsn) && options.whitelistPrefixes.any { bsn.startsWith(it) }) {
          val nav = target.bundlesByBsn()[bsn]
          val rb = nav?.lastEntry()?.value
          if (rb != null) {
            selected[bsn] = Candidate(bsn, rb.manifest.bundleVersion, rb.location, rb.manifest, false)
          }
        }
      }
    }

    fun toResolved(c: Candidate) = ResolvedBundle(
      bsn = c.bsn,
      version = c.version,
      path = c.path,
      isWorkspace = c.isWorkspace,
      isHost = c.isHost
    )

    val bundles = mutableListOf<ResolvedBundle>()
    // Host first, if any
    val hostBsn = hostPair?.first
    if (hostBsn != null) selected[hostBsn]?.let { bundles.add(toResolved(it)) }
    // Then others in insertion order
    selected.forEach { (bsn, cand) ->
      if (bsn != hostBsn) bundles.add(toResolved(cand))
    }

    return ResolveResult(
      bundles = bundles,
      imports = imports,
      requires = requires,
      unresolved = unresolved.toList()
    )
  }
}
