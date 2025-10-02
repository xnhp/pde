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
  val manifest: BundleManifest
)

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
  val requires: Map<String, VersionRange>
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

    // If entry is a fragment, include its host first (optional)
    val hostPair = entry.manifest.fragmentHostAndVersionRange()
    if (options.includeHostsForFragments && hostPair != null) {
      val (hostBsn, hostRange) = hostPair
      val host = select(hostBsn, hostRange)
      if (host != null) {
        selected.putIfAbsent(hostBsn, host.copy(isHost = true))
      }
    }

    fun addRequireWithClosure(bsn: String, range: VersionRange) {
      if (!selected.containsKey(bsn)) {
        val cand = select(bsn, range)
        if (cand != null) {
          selected.putIfAbsent(bsn, cand)
          // Re-export closure from the selected candidate
          val reexports = cand.manifest.reexportRequiredBundleAndVersion()
          for ((rb, rr) in reexports) addRequireWithClosure(rb, rr)
        }
      }
    }

    val requires = entry.manifest.requiredBundleAndVersion()
    requires.forEach { (bsn, range) -> addRequireWithClosure(bsn, range) }

    // Import-Package resolution: pick providers for requested packages
    val imports = entry.manifest.importedPackageAndVersion()

    fun findProviderForPackage(pkg: String, range: VersionRange): Candidate? {
      if (options.preferWorkspace) {
        val wsProvider = workspace.asSequence()
          .mapNotNull { desc ->
            val exports = desc.manifest.exportedPackageAndVersion()
            val v = exports[pkg]
            if (v != null && range.includes(v)) desc else null
          }
          .maxByOrNull { it.manifest.bundleVersion }
        if (wsProvider != null) {
          val bsn = wsProvider.manifest.bundleSymbolicName?.key ?: return null
          return Candidate(
            bsn = bsn,
            version = wsProvider.manifest.bundleVersion,
            path = wsProvider.path,
            manifest = wsProvider.manifest,
            isWorkspace = true
          )
        }
      }

      // Target platform scan: choose highest bundle that exports matching package
      val byBsn = target.bundlesByBsn()
      var best: Candidate? = null
      for ((bsn, nav) in byBsn) {
        val desc = nav.descendingMap()
        for ((ver, rb) in desc) {
          val exp = rb.manifest.exportedPackageAndVersion()
          val pkgVer = exp[pkg] ?: continue
          if (!range.includes(pkgVer)) continue
          best = Candidate(
            bsn = bsn,
            version = ver,
            path = rb.location,
            manifest = rb.manifest,
            isWorkspace = false
          )
          break
        }
        if (best != null) break
      }
      return best
    }

    imports.forEach { (pkg, range) ->
      val provider = findProviderForPackage(pkg, range)
      if (provider != null && !selected.containsKey(provider.bsn)) {
        selected[provider.bsn] = provider
      }
    }

    if (options.whitelistPrefixes.isNotEmpty()) {
      for (prefix in options.whitelistPrefixes) {
        target.bundlesByBsn().forEach { (bsn, nav) ->
          if (bsn.startsWith(prefix) && !selected.containsKey(bsn)) {
            nav.lastEntry()?.value?.let { rb ->
              selected[bsn] = Candidate(
                bsn = bsn,
                version = rb.manifest.bundleVersion,
                path = rb.location,
                manifest = rb.manifest,
                isWorkspace = false
              )
            }
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
      requires = requires
    )
  }
}
