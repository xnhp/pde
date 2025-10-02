package cn.varsa.idea.pde.partial.plugin.support

import cn.varsa.pde.resolver.manifest.BundleManifest
import cn.varsa.pde.resolver.manifest.requiredBundleAndVersion
import cn.varsa.pde.resolver.manifest.reexportRequiredBundleAndVersion
import cn.varsa.pde.resolver.support.VersionRangeAny
import cn.varsa.pde.resolver.support.contains
import cn.varsa.idea.pde.partial.common.support.ifTrue
import cn.varsa.idea.pde.partial.plugin.cache.BundleManifestCacheService
import cn.varsa.idea.pde.partial.plugin.config.BundleManagementService
import com.intellij.openapi.module.Module
import com.intellij.openapi.project.Project
import org.osgi.framework.Version
import org.osgi.framework.VersionRange

fun BundleManifest.isBundleRequiredOrFromReExport(
  project: Project, module: Module?, symbolName: String, version: Set<Version> = emptySet()
): Boolean {
  val cacheService = BundleManifestCacheService.getInstance(project)
  val managementService = BundleManagementService.getInstance(project)

  // Bundle required directly
  requiredBundleAndVersion().any { (k, r) ->
    k == symbolName && (version.isEmpty() || version.any { it in r })
  }.ifTrue { return true }

  val requiredBundle = requireBundle?.keys ?: return false
  val allRequiredFromReExport = managementService.getLibReExportRequired(requiredBundleAndVersion())

  // Re-export dependency tree can resolve bundle
  allRequiredFromReExport.contains(symbolName).ifTrue { return true }

  // Re-export bundle contain module, it needs calc again
  val modulesManifest = project.allPDEModules(module).mapNotNull(cacheService::getManifest).toHashSet()

  modulesManifest.filter {
    it.bundleSymbolicName?.key?.run { requiredBundle.contains(this) || allRequiredFromReExport.contains(this) } == true
  }.any { isBundleFromReExportOnly(it, symbolName, version, cacheService, managementService, modulesManifest) }
    .ifTrue { return true }

  return false
}

private fun isBundleFromReExportOnly(
  manifest: BundleManifest,
  symbolName: String,
  version: Set<Version>,
  cacheService: BundleManifestCacheService,
  managementService: BundleManagementService,
  modulesManifest: HashSet<BundleManifest>
): Boolean {
  // Re-export directly
  manifest.reexportRequiredBundleAndVersion().filterValues { range -> version.isEmpty() || version.any { it in range } }
    .containsKey(symbolName).ifTrue { return true }

  val allReExport = managementService.getLibReExportRequired(manifest.reexportRequiredBundleAndVersion())

  // Re-export dependency tree can resolve bundle
  allReExport.contains(symbolName).ifTrue { return true }

  // Dependency tree contains module, it needs calc again, and remove it from module set to not calc again and again and again
  return modulesManifest.filter { allReExport.contains(it.bundleSymbolicName?.key) }.toSet()
    .also { modulesManifest -= it }
    .any { isBundleFromReExportOnly(it, symbolName, version, cacheService, managementService, modulesManifest) }
}

fun BundleManifest.bundleRequiredOrFromReExportOrderedList(
  project: Project, vararg exclude: Module? = emptyArray()
): LinkedHashSet<Pair<String, Version>> {
  val cacheService = BundleManifestCacheService.getInstance(project)
  val managementService = BundleManagementService.getInstance(project)

  val result = linkedSetOf<Pair<String, Version>>()

  val modulesManifest = project.allPDEModules(*exclude).mapNotNull { cacheService.getManifest(it) }
    .associate { it.bundleSymbolicName?.key to (it.bundleVersion to it) }.toMutableMap()

  fun processBSN(
    exportBundle: String, range: VersionRange, onEach: (Map.Entry<String, VersionRange>) -> Unit
  ) {
    managementService.getBundlesByBSN(exportBundle, range)?.let { result += it.bundleSymbolicName to it.bundleVersion }

    modulesManifest[exportBundle]?.takeIf { it.first in range }
      ?.also { modulesManifest -= exportBundle }?.second?.also { result += exportBundle to it.bundleVersion }
      ?.requiredBundleAndVersion()?.forEach { onEach(it) }
  }

  fun cycleBSN(exportBundle: String, range: VersionRange) {
    processBSN(exportBundle, range) { cycleBSN(it.key, it.value) }

    managementService.getLibReExportRequired(exportBundle, range)?.forEach { (bsn, reqRange) ->
      processBSN(bsn, reqRange) { cycleBSN(it.key, it.value) }
    }
  }

  requiredBundleAndVersion().forEach { cycleBSN(it.key, it.value) }

  return result
}
