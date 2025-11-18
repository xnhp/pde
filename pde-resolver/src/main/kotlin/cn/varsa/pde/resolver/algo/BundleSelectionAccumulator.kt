package cn.varsa.pde.resolver.algo

import org.osgi.framework.Version
import java.nio.file.Path

/**
 * Accumulates resolver outputs and supplemental bundles while enforcing workspace precedence
 * and duplicate suppression so multiple consumers can share the same selection semantics.
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
