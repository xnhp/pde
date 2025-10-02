package cn.varsa.idea.pde.partial.plugin.resolver

import cn.varsa.idea.pde.partial.common.*
import cn.varsa.pde.resolver.manifest.BundleManifest
import cn.varsa.pde.resolver.manifest.canonicalName
import cn.varsa.pde.resolver.manifest.exportedPackageAndVersion
import cn.varsa.pde.resolver.manifest.fragmentHostAndVersionRange
import cn.varsa.pde.resolver.manifest.importedPackageAndVersion
import cn.varsa.pde.resolver.manifest.requiredBundleAndVersion
import cn.varsa.pde.resolver.algo.Resolver
import cn.varsa.pde.resolver.algo.ResolveOptions
import cn.varsa.pde.resolver.algo.WorkspaceBundleDescriptor
import cn.varsa.pde.resolver.index.TargetPlatformIndex
import cn.varsa.pde.resolver.index.ResolvedBundle as TargetResolvedBundle
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

    val manifestByBsn = mutableMapOf<String, BundleManifest?>()

    fun resolveTransitiveDependencies(requiredBundles: Set<String>, resolvedBundles: MutableSet<String>) {
      // TODO share sub-results (DP)
      val newDependencies = mutableSetOf<String>()
      requiredBundles.forEach { bsn ->
        if (bsn !in resolvedBundles) {
          val manifest = manifestByBsn.getOrPut(bsn) {
            managementService.getBundleManifestBySymbolicName(bsn)
          }
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

      // Build workspace descriptors (all PDE modules in project)
      val allPdeModules = project.allPDEModules()
      val moduleByBsn = allPdeModules.mapNotNull { m ->
        val man = cacheService.getManifest(m)
        val bsn = man?.bundleSymbolicName?.key
        if (bsn != null) bsn to m else null
      }.toMap()

      fun modulePath(m: Module) =
        m.moduleRootManager.contentRoots.firstOrNull()?.path?.let { java.nio.file.Paths.get(it) }

      val workspace: List<WorkspaceBundleDescriptor> = allPdeModules.mapNotNull { m ->
        val man = cacheService.getManifest(m) ?: return@mapNotNull null
        val path = modulePath(m) ?: return@mapNotNull null
        WorkspaceBundleDescriptor(path, man)
      }

      val entryPath = modulePath(area) ?: return@updateModel
      val entryDesc = WorkspaceBundleDescriptor(entryPath, bundleManifest)

      // Build TargetPlatformIndex from current target bundles (cached in plugin)
      val targetBundles = managementService.getBundles()
      val byBsn: MutableMap<String, java.util.NavigableMap<Version, TargetResolvedBundle>> = hashMapOf()
      targetBundles.forEach { b ->
        val man = b.manifest ?: return@forEach
        // Exclude source bundles
        if (man.eclipseSourceBundle != null) return@forEach
        val bsn = man.bundleSymbolicName?.key ?: return@forEach
        val ver = man.bundleVersion
        val rb = TargetResolvedBundle(b.file.toPath(), man, b.file.isDirectory)
        byBsn.computeIfAbsent(bsn) { java.util.TreeMap() }[ver] = rb
      }
      val targetIndex = TargetPlatformIndex(byBsn)

      val options = ResolveOptions(
        whitelistPrefixes = PreferenceService.getInstance(project).libraryWhitelist,
        preferWorkspace = true,
        includeHostsForFragments = true
      )

      val result = Resolver.resolve(targetIndex, workspace, entryDesc, options)

      // Prepare project library index (BSN -> versions -> Library)
      val index = ProjectLibraryIndexService.getInstance(project).getIndex()
      val libsByBsn: Map<String, java.util.NavigableMap<Version, Library>> = if (index.isNotEmpty()) {
        index
      } else {
        // Fallback: ad-hoc rebuild view from current project libs
        val tableLibs = project.libraryTable().libraries
        val map = hashMapOf<String, java.util.NavigableMap<Version, Library>>()
        tableLibs.filter { it.name?.startsWith(ProjectLibraryNamePrefix) == true }.forEach { lib ->
          val name = lib.name ?: return@forEach
          val bsn = bsnOfLibraryName(name) ?: return@forEach
          val verText = name.substringAfterLast(BundleDefinition.canonicalNameSeparator)
          val ver = try { Version.parseVersion(verText) } catch (_: Exception) { null }
          if (ver != null) map.computeIfAbsent(bsn) { java.util.TreeMap() }[ver] = lib
        }
        map
      }

      // If entry is a fragment with a workspace host, mirror host dependencies
      val hostWorkspaceBsn = result.bundles.firstOrNull { it.isHost && it.isWorkspace }?.bsn
      val hostLibraries: MutableList<Library> = mutableListOf()
      if (hostWorkspaceBsn != null) {
        val hostModule = moduleByBsn[hostWorkspaceBsn]
        if (hostModule != null) {
          val hostOrderEntries = hostModule.moduleRootManager.orderEntries
          hostOrderEntries.filterIsInstance<ModuleOrderEntry>().forEach { entry ->
            entry.module?.let { depModule ->
              if (depModule == area) return@let
              val moduleDependency = addModuleDependency(depModule)
              moduleDependency.scope = entry.scope
              moduleDependency.isExported = entry.isExported
              moduleDependency.isProductionOnTestDependency = entry.isProductionOnTestDependency
            }
          }
          hostOrderEntries.filterIsInstance<LibraryOrderEntry>().forEach { entry ->
            entry.library?.let { lib -> hostLibraries += lib }
          }
        }
      }

      // Apply workspace module dependencies
      result.bundles.filter { it.isWorkspace }.forEach { rb ->
        val mod = moduleByBsn[rb.bsn]
        if (mod != null && mod != area) addModuleDependency(mod)
      }

      // Apply target libraries (choose exact version where possible)
      val addedLibs = hashSetOf<Library>()
      fun chooseLib(bsn: String, ver: Version): Library? {
        val nav = libsByBsn[bsn] ?: return null
        return nav[ver] ?: nav.lastEntry()?.value
      }

      result.bundles.filter { !it.isWorkspace }.forEach { rb ->
        val lib = chooseLib(rb.bsn, rb.version)
        if (lib != null && addedLibs.add(lib)) {
          model.addLibraryEntry(lib)
        }
      }

      // Merge in host libraries when present
      hostLibraries.forEach { lib ->
        if (addedLibs.add(lib)) model.addLibraryEntry(lib)
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
        manifest.bundleRequiredOrFromReExportOrderedList(project, area).map { it.asCanonicalName }.flatMap {
          val dash = it
          val at = it.substringBeforeLast('-') +
            cn.varsa.idea.pde.partial.plugin.domain.BundleDefinition.canonicalNameSeparator +
            it.substringAfterLast('-')
          listOf(dash, "$ProjectLibraryNamePrefix$dash", "$ProjectLibraryNamePrefix$at")
        }.mapNotNull { orderEntriesMap[it] }

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
