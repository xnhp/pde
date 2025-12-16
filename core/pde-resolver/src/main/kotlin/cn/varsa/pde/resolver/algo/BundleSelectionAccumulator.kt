package cn.varsa.pde.resolver.algo

import org.osgi.framework.Version
import java.nio.file.Path

/**
 * Accumulates resolver outputs (and optional supplemental bundles) while enforcing
 * workspace-over-target precedence plus duplicate suppression.  This lets every consumer
 * (IDE module resolvers, LaunchPlanner, future CLI flows) share identical semantics when
 * deciding which BSNs/versions should appear in the runtime/compile classpath.
 *
 * Conceptually, the accumulator:
 *  - Collects {@link ResolveResult} bundles and ad-hoc injections (startup requirements,
 *    supplemental libraries) keyed by BSN.
 *  - Ensures workspace bundles replace target ones for the same BSN when
 *    {@code preferWorkspace} is enabled.
 *  - Deduplicates entries that point at the same (version, path) pair so later consumers
 *    don't reimplement suppression logic.
 *  - Exposes helper methods such as {@link #ensureBundle} so callers can lazily fetch
 *    missing bundles (e.g., from the target index) only when needed.
 */
class BundleSelectionAccumulator(private val preferWorkspace: Boolean = true) {
  private data class CandidateKey(val version: Version, val path: Path)

  private val selections: LinkedHashMap<String, MutableList<ResolvedBundle>> = linkedMapOf()

  fun add(result: ResolveResult) {
    result.bundles.forEach { register(it) }
  }

  fun register(bundle: ResolvedBundle) {
    val list = selections.getOrPut(bundle.bsn) { mutableListOf() }
    if (bundle.isWorkspace && preferWorkspace) {
      list.clear()
      list += bundle
      return
    }
    if (list.any { it.isWorkspace && preferWorkspace }) return
    val key = CandidateKey(bundle.version, bundle.path)
    if (list.none { CandidateKey(it.version, it.path) == key }) {
      list += bundle
    }
  }

  fun registerSupplemental(
    bsn: String,
    version: Version,
    path: Path,
    isWorkspace: Boolean = false,
    classPathEntries: List<Path> = listOf(path)
  ) {
    register(
      ResolvedBundle(
        bsn = bsn,
        version = version,
        path = path,
        origin = if (isWorkspace) BundleOrigin.WORKSPACE else BundleOrigin.TARGET,
        classPathEntries = classPathEntries,
        sourceEntries = emptyList(),
        fragmentHost = null,
        isHost = false
      )
    )
  }

  fun contains(bsn: String): Boolean = selections.containsKey(bsn)

  fun ensureBundle(bsn: String, provider: () -> ResolvedBundle?): Boolean {
    if (contains(bsn)) return true
    val provided = provider() ?: return false
    register(provided)
    return true
  }

  fun entries(): List<ResolvedBundle> = selections.values.flatten()
}
