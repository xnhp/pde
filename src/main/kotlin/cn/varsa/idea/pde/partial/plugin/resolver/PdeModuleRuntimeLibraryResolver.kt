package cn.varsa.idea.pde.partial.plugin.resolver

import cn.varsa.idea.pde.partial.common.*
import cn.varsa.idea.pde.partial.common.domain.BundleManifest
import cn.varsa.idea.pde.partial.common.support.*
import cn.varsa.idea.pde.partial.plugin.cache.*
import cn.varsa.idea.pde.partial.plugin.config.*
import cn.varsa.idea.pde.partial.plugin.domain.*
import cn.varsa.idea.pde.partial.plugin.facet.*
import cn.varsa.idea.pde.partial.plugin.i18n.EclipsePDEPartialBundles.message
import cn.varsa.idea.pde.partial.plugin.openapi.resolver.*
import cn.varsa.idea.pde.partial.plugin.support.*
import com.intellij.openapi.module.*
import com.intellij.openapi.roots.*
import com.intellij.openapi.roots.libraries.*
import com.intellij.util.containers.ContainerUtil.*
import org.osgi.framework.Version
import cn.varsa.idea.pde.partial.plugin.config.ProjectLibraryIndexService

class PdeModuleRuntimeLibraryResolver : ManifestLibraryResolver {
  override val displayName: String = message("resolver.pde.moduleRuntime")

  override fun preResolve(area: Module) {
    PDEFacet.getInstance(area) ?: return

    area.updateModel { model ->
      model.orderEntries.filter { it is ModuleOrderEntry || it.presentableName.startsWith(ProjectLibraryNamePrefix) || it.presentableName.equals(ModuleLibraryName) }
        .forEach { model.removeOrderEntry(it) }
    }
  }

  // TODO can we introduce an action that modifies _only_ this module? does this make sense?
  override fun resolve(area: Module) {
    PDEFacet.getInstance(area) ?: return

    val project = area.project
    val cacheService = BundleManifestCacheService.getInstance(project)
    val managementService = BundleManagementService.getInstance(project)
    val bundleManifest = cacheService.getManifest(area) ?: return

    val classesRoot = bundleManifest.bundleClassPath?.keys?.filterNot { it == "." }?.flatMap { binaryName ->
      area.moduleRootManager.contentRoots.mapNotNull { it.findFileByRelativePath(binaryName) }
    }?.map { it.protocolUrl }?.distinct() ?: emptyList()

    fun bsnOfLibraryName(name: String): String? {
      // e.g. "Partial: org.eclipse.birt.report.designer.ui.preview.web-4.15.0.v202403260939"
      return name.substringAfterLast(ProjectLibraryNamePrefix).substringBeforeLast(BundleDefinition.canonicalNameSeparator)
    }

    fun bsnOfLibrary(it: Library): String? {
      return it.name?.let { bsnOfLibraryName(it) }
    }

    fun resolveTransitiveDependencies(requiredBundles: Set<String>, resolvedBundles: MutableSet<String>) {
      // TODO share sub-results (DP)
      val newDependencies = mutableSetOf<String>()
      requiredBundles.forEach { bsn ->
        if (bsn !in resolvedBundles) {
          val manifest = managementService.getBundleManifestBySymbolicName(bsn)
          manifest?.requireBundle?.keys?.let { newDependencies.addAll(it) }
          resolvedBundles.add(bsn)
        }
      }
      if (newDependencies.isNotEmpty()) {
        resolveTransitiveDependencies(newDependencies, resolvedBundles)
      }
    }

    area.updateModel { model ->

      fun addModuleDependency(moduleToAdd: Module): ModuleOrderEntry {
        model.moduleDependencies.contains(moduleToAdd)
        return model.findModuleOrderEntry(moduleToAdd) ?: model.addModuleOrderEntry(moduleToAdd)
      }

      val libraryTableModel = model.moduleLibraryTable.modifiableModel

      applicationInvokeAndWait {
        // "Partial-Runtime"
        val library = libraryTableModel.getLibraryByName(ModuleLibraryName) ?: writeCompute {
          libraryTableModel.createLibrary(ModuleLibraryName)
        }
        model.findLibraryOrderEntry(library)?.apply {
          scope = DependencyScope.COMPILE
          isExported = true
        }

        val libraryModel = library.modifiableModel

        libraryModel.getUrls(OrderRootType.CLASSES).forEach { libraryModel.removeRoot(it, OrderRootType.CLASSES) }
        classesRoot.forEach { libraryModel.addRoot(it, OrderRootType.CLASSES) }

        writeRun {
          libraryModel.commit()
          libraryTableModel.commit()
        }
      }

      val orderedList =
        bundleManifest.bundleRequiredOrFromReExportOrderedList(project, area)
      val importedList = bundleManifest.importedPackageAndVersion()
      val hostAndRange = bundleManifest.fragmentHostAndVersionRange()
      // Cache PDE modules once to avoid repeated scans
      val allPdeModules = project.allPDEModules(area)
      applicationInvokeAndWait {
        allPdeModules.filter { module ->
          val manifest = cacheService.getManifest(module)
          val bsn = manifest?.bundleSymbolicName?.key
          val version = manifest?.bundleVersion
          val range = manifest?.fragmentHostAndVersionRange()

          (bsn == hostAndRange?.first && version in hostAndRange?.second)  // add fragment host as module dependency
              || orderedList.any { //
            (it.first == bsn && version == it.second)  //
                || (it.first == range?.first && it.second in range.second)
          }
              || manifest?.exportedPackageAndVersion()?.any {
              (packageName, version) -> version in importedList[packageName]
          } == true
        }.forEach {
          model.addModuleOrderEntry(it)
        }
      }

      // Collect host dependencies for fragments without directly adding
      // libraries here; they will be merged and de-duplicated later.
      val hostLibraries: MutableList<Library> = mutableListOf()
      bundleManifest.fragmentHostAndVersionRange()
        ?.let { (hostSymbolicName, hostVersionRange) ->
          val hostModule = allPdeModules.find { candidate ->
            val man = cacheService.getManifest(candidate)
            val bsn = man?.bundleSymbolicName?.key
            val ver = man?.bundleVersion
            bsn == hostSymbolicName && (ver in hostVersionRange)
          }
          if (hostModule != null) {
            val hostOrderEntries = hostModule.moduleRootManager.orderEntries
            // Inherit host’s module dependencies
            hostOrderEntries.filterIsInstance<ModuleOrderEntry>().forEach { entry ->
              entry.module?.let { depModule ->
                if (depModule != area) addModuleDependency(depModule)
              }
            }
            // Record host’s library dependencies for later selection
            hostOrderEntries.filterIsInstance<LibraryOrderEntry>().forEach { entry ->
              entry.library?.let { lib -> hostLibraries += lib }
            }
          }
        }



      // initial seed
      val requiredBundles = bundleManifest.requireBundle?.map { it.key }?.toSet() ?: emptySet()
      val seeds = requiredBundles
      // accumulator for transitive dependencies
      val resolvedBundles = mutableSetOf<String>()
      resolveTransitiveDependencies(seeds, resolvedBundles)
      val imported = bundleManifest.importPackage?.map { it.key }?.toSet() ?: emptySet()
      val bundleDependencies = resolvedBundles.union(imported)

      // add module dependency if there is such a module in the project
      allPdeModules.filter { module ->
        val manifest = cacheService.getManifest(module)
        val bsn = manifest?.bundleSymbolicName?.key
        bundleDependencies.contains(bsn)
      }.forEach {
        model.addModuleOrderEntry(it)
      }

      val whitelistedBundles: Set<String> =
        PreferenceService.getInstance(project).libraryWhitelist

      // Exclude bundles that are represented by modules (by BSN, not module name)
      val pdeModuleBSNs: Set<String> = allPdeModules.mapNotNull {
        cacheService.getManifest(it)?.bundleSymbolicName?.key
      }.toSet()

      // Group available project libraries by BSN and capture their versions
      data class LibVer(val lib: Library, val ver: Version)

      fun versionOfLibraryName(name: String): Version? {
        val tail = name.substringAfterLast(ProjectLibraryNamePrefix)
        val versionText =
          tail.substringAfterLast(BundleDefinition.canonicalNameSeparator)
        return try { Version.parseVersion(versionText) } catch (e: Exception) {
          null
        }
      }

      val index = ProjectLibraryIndexService.getInstance(project).getIndex()

      val libsByBsn: Map<String, List<LibVer>> = if (index.isNotEmpty()) {
        index.mapValues { (_, nav) ->
          nav.entries.map { LibVer(it.value, it.key) }
        }
      } else {
        // Fallback: build ad-hoc map if index is not ready
        val projectLibs = project.libraryTable().libraries
          .filter { it.name?.startsWith(ProjectLibraryNamePrefix) == true }
        projectLibs.mapNotNull { lib ->
          val name = lib.name ?: return@mapNotNull null
          val bsn = bsnOfLibraryName(name) ?: return@mapNotNull null
          val ver = versionOfLibraryName(name) ?: return@mapNotNull null
          bsn to LibVer(lib, ver)
        }.groupBy({ it.first }, { it.second })
      }

      // Capture manifest + export lookups per library so we can reuse them when
      // only import-package dependencies are present.
      val libraryManifestCache = mutableMapOf<Library, BundleManifest?>()
      val libraryExportsCache = mutableMapOf<Library, Map<String, Version>>()

      fun libraryManifestOf(library: Library): BundleManifest? =
        libraryManifestCache.getOrPut(library) {
          library.getFiles(OrderRootType.CLASSES).asSequence()
            .mapNotNull { cacheService.getManifest(it) }
            .firstOrNull()
        }

      fun libraryExportsOf(library: Library): Map<String, Version> =
        libraryExportsCache.getOrPut(library) {
          libraryManifestOf(library)?.exportedPackageAndVersion() ?: emptyMap()
        }

      // Build selection of BSN -> exact Version.
      // Prefer direct Require-Bundle constraints over re-exports to avoid
      // older transitive versions overriding a module's explicit version
      // requirement. We first resolve exact versions only for directly
      // required bundles, and then fall back to re-exported/transitive
      // results (orderedList) for BSNs that remain unspecified.
      val requiredBsnToVersion: LinkedHashMap<String, Version> = linkedMapOf()
      val availableBundles = managementService.getBundles()

      // 1) Direct Require-Bundle: resolve to an exact, highest-in-range
      //    version available in the target platform. This establishes the
      //    authoritative version for the BSN when both direct and re-export
      //    information exist.
      bundleManifest.requiredBundleAndVersion().forEach { (bsn, range) ->
        managementService.getBundlesByBSN(bsn, range)?.bundleVersion?.let { v ->
          requiredBsnToVersion.putIfAbsent(bsn, v)
        }
      }

      // 1a) Import-Package: choose bundles that export the requested
      //     packages within the declared version range. This covers the
      //     case where manifest only imports packages (no Require-Bundle)
      //     and ensures we still attach the provider bundle/library.
      importedList.forEach { (packageName, range) ->
        val candidate = availableBundles.asSequence()
          .mapNotNull { candidateBundle ->
            val exportedVersion =
              candidateBundle.manifest?.exportedPackageAndVersion()?.get(packageName)
                ?: return@mapNotNull null
            if (!(range contains exportedVersion)) return@mapNotNull null
            candidateBundle to exportedVersion
          }
          .maxWithOrNull(compareBy({ it.second }, { it.first.bundleVersion }))
        candidate?.first?.let { bundle ->
          requiredBsnToVersion.putIfAbsent(bundle.bundleSymbolicName, bundle.bundleVersion)
        }

        if (candidate == null && libsByBsn.isNotEmpty()) {
          // No bundle in the target platform serves this package; attempt to
          // resolve against Partial libraries registered in the project.
          // This is useful for handling "Import-Package" directives
          val fallback = libsByBsn.asSequence()
            .flatMap { (bsn, libVersions) ->
              libVersions.asSequence().mapNotNull { libVer ->
                val manifest = libraryManifestOf(libVer.lib) ?: return@mapNotNull null
                val exportedVersion = libraryExportsOf(libVer.lib)[packageName]
                  ?: return@mapNotNull null
                if (!(range contains exportedVersion)) return@mapNotNull null
                Triple(bsn, manifest.bundleVersion, exportedVersion)
              }
            }
            .maxWithOrNull(compareBy({ it.third }, { it.second }))

          fallback?.let { (bsn, version, _) ->
            requiredBsnToVersion.putIfAbsent(bsn, version)
          }
        }
      }

      // 2) Re-exports and other transitives from the ordered list are only
      //    used to fill in BSNs that do not have a direct constraint.
      orderedList.forEach { (bsn, ver) ->
        requiredBsnToVersion.putIfAbsent(bsn, ver)
      }

      // Seed BSNs to consider with required ones
      val bsnsToConsider: MutableSet<String> = requiredBsnToVersion.keys.toMutableSet()

      // Include host libraries’ BSNs
      hostLibraries.forEach { lib ->
        val name = lib.name
        if (name != null && name.startsWith(ProjectLibraryNamePrefix)) {
          bsnOfLibraryName(name)?.let { bsnsToConsider += it }
        }
      }

      // Include whitelisted bundle prefixes
      libsByBsn.keys.forEach { bsn ->
        if (whitelistedBundles.any { prefix -> bsn.startsWith(prefix) }) {
          bsnsToConsider += bsn
        }
      }

      // Remove any BSN that is provided by a module
      bsnsToConsider.removeAll(pdeModuleBSNs)

      // Choose a single library per BSN (prefer required version, else max)
      val selectedLibsByBsn: MutableMap<String, Library> = mutableMapOf()

      bsnsToConsider.forEach { bsn ->
        val candidates = libsByBsn[bsn] ?: return@forEach
        val preferred = requiredBsnToVersion[bsn]
        val chosen = preferred?.let { pv ->
          candidates.firstOrNull { it.ver == pv }?.lib
        } ?: candidates.maxByOrNull { it.ver }?.lib
        if (chosen != null) selectedLibsByBsn.putIfAbsent(bsn, chosen)
      }

      // Merge in host libraries when not already chosen (same BSN)
      hostLibraries.forEach { lib ->
        val name = lib.name ?: return@forEach
        if (!name.startsWith(ProjectLibraryNamePrefix)) return@forEach
        val bsn = bsnOfLibraryName(name) ?: return@forEach
        if (bsn in pdeModuleBSNs) return@forEach
        // Prefer the exact library instance used by the host to avoid
        // fragments using a different version selected earlier.
        selectedLibsByBsn[bsn] = lib
      }

      // Finally, add the selected libraries only once per BSN
      selectedLibsByBsn.values.forEach { depLibrary ->
        model.addLibraryEntry(depLibrary)
      }
    }
  }

  override fun postResolve(area: Module) {
    PDEFacet.getInstance(area) ?: return

    val project = area.project
    val cacheService = BundleManifestCacheService.getInstance(project)
    val manifest = cacheService.getManifest(area) ?: return
    val hostBCN = project.fragmentHostManifest(manifest, area)?.canonicalName

    area.updateModel { model ->
      val orderEntries = model.orderEntries.toMutableList()
      val orderEntriesMap = orderEntries.associateBy { it.presentableName }

      val kotlinOrder = orderEntriesMap.filter { it.key.startsWith(KotlinOrderEntryName) }.values.toSet()
      val runtimeOrder = orderEntriesMap[ModuleLibraryName]
      val hostOrder = orderEntriesMap[hostBCN] ?: orderEntriesMap["$ProjectLibraryNamePrefix$hostBCN"]
      val dependencyOrder =
        manifest.bundleRequiredOrFromReExportOrderedList(project, area).map { it.asCanonicalName }.mapNotNull {
          orderEntriesMap[it] ?: orderEntriesMap["$ProjectLibraryNamePrefix$it"]
        }

      var libraryIndex = orderEntries.indexOfLast { it is JdkOrderEntry || it is ModuleSourceOrderEntry } + 1
      val arrangeOrderEntries = orderEntries.apply {
        removeAll(kotlinOrder)
        addAll(libraryIndex, kotlinOrder)
        libraryIndex += kotlinOrder.size

        runtimeOrder?.also {
          remove(it)
          add(libraryIndex++, it)
        }
        hostOrder?.also {
          remove(it)
          add(libraryIndex++, it)
        }

        removeAll(dependencyOrder)
        addAll(libraryIndex, dependencyOrder)
      }.toTypedArray()
      model.rearrangeOrderEntries(arrangeOrderEntries)
    }
  }
}
