package cn.varsa.idea.pde.partial.plugin.support

import cn.varsa.pde.resolver.manifest.BundleManifest
import cn.varsa.pde.resolver.manifest.requiredBundleAndVersion
import cn.varsa.pde.resolver.manifest.isBundleRequired
import cn.varsa.pde.resolver.manifest.reexportRequiredBundleAndVersion
import cn.varsa.pde.resolver.support.VersionRangeAny
import cn.varsa.pde.resolver.support.contains
import cn.varsa.idea.pde.partial.common.support.ifTrue
import cn.varsa.idea.pde.partial.plugin.cache.BundleManifestCacheService
import cn.varsa.idea.pde.partial.plugin.config.PluginTargetIndexService
import com.intellij.openapi.module.Module
import com.intellij.openapi.project.Project
import org.osgi.framework.Version
import org.osgi.framework.VersionRange

fun BundleManifest.isBundleRequiredOrFromReExport(
  project: Project, module: Module?, symbolName: String, version: Set<Version> = emptySet()
): Boolean {
  val cacheService = BundleManifestCacheService.getInstance(project)
  val tpService = PluginTargetIndexService.getInstance(project)

  // Bundle required directly
  requiredBundleAndVersion().any { (k, r) ->
    k == symbolName && (version.isEmpty() || version.any { it in r })
  }.ifTrue { return true }

  val requiredBundle = requireBundle?.keys ?: return false
  // Build re-export closure on the fly across workspace and target candidates
  val modulesManifest = project.allPDEModules(module).mapNotNull(cacheService::getManifest).toHashSet()

  fun traverseReexport(man: BundleManifest, visited: MutableSet<String>): Boolean {
    val reexp = man.reexportRequiredBundleAndVersion()
    // direct match
    if (reexp.any { (k, r) -> k == symbolName && (version.isEmpty() || version.any { it in r }) }) return true
    // follow re-export edges
    for ((bsn, range) in reexp) {
      if (!visited.add(bsn)) continue
      // Prefer workspace module manifests if present
      val nextModule = modulesManifest.firstOrNull { it.bundleSymbolicName?.key == bsn && it.bundleVersion in range }
      if (nextModule != null) {
        if (traverseReexport(nextModule, visited)) return true
        continue
      }
      val next = tpService.getBundlesByBSN(bsn, range)?.manifest ?: continue
      if (traverseReexport(next, visited)) return true
    }
    return false
  }

  if (traverseReexport(this, hashSetOf())) return true

  return false
}

// legacy helper removed: re-export closure now built using core index

fun BundleManifest.bundleRequiredOrFromReExportOrderedList(
  project: Project, vararg exclude: Module? = emptyArray()
): LinkedHashSet<Pair<String, Version>> {
  val cacheService = BundleManifestCacheService.getInstance(project)
  val tpService = PluginTargetIndexService.getInstance(project)

  val result = linkedSetOf<Pair<String, Version>>()

  val modulesManifest = project.allPDEModules(*exclude).mapNotNull { cacheService.getManifest(it) }
    .associate { it.bundleSymbolicName?.key to (it.bundleVersion to it) }.toMutableMap()

  fun processBSN(
    exportBundle: String, range: VersionRange, onEach: (Map.Entry<String, VersionRange>) -> Unit
  ) {
    tpService.getBundlesByBSN(exportBundle, range)?.let { result += exportBundle to it.manifest.bundleVersion }

    modulesManifest[exportBundle]?.takeIf { it.first in range }
      ?.also { modulesManifest -= exportBundle }?.second?.also { result += exportBundle to it.bundleVersion }
      ?.requiredBundleAndVersion()?.forEach { onEach(it) }
  }

  fun cycleBSN(exportBundle: String, range: VersionRange) {
    processBSN(exportBundle, range) { cycleBSN(it.key, it.value) }

    // Traverse workspace manifests and target candidates alike
    modulesManifest.values.map { it.second }.filter { it.isBundleRequired(exportBundle) }.forEach { man ->
      man.requiredBundleAndVersion().forEach { (k, r) -> processBSN(k, r) { cycleBSN(it.key, it.value) } }
    }
    tpService.getBundlesByBSN(exportBundle)?.values?.lastOrNull()?.manifest
      ?.requiredBundleAndVersion()?.forEach { (k, r) -> processBSN(k, r) { cycleBSN(it.key, it.value) } }
  }

  requiredBundleAndVersion().forEach { cycleBSN(it.key, it.value) }

  return result
}
