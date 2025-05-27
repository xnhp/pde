package cn.varsa.idea.pde.partial.plugin.resolver

import cn.varsa.idea.pde.partial.common.*
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

      val orderedList = bundleManifest.bundleRequiredOrFromReExportOrderedList(project, area)
      val importedList = bundleManifest.importedPackageAndVersion()
      val hostAndRange = bundleManifest.fragmentHostAndVersionRange()
      applicationInvokeAndWait {

        project.allPDEModules(area).filter { module ->
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

      bundleManifest.fragmentHostAndVersionRange()
        ?.let { (hostSymbolicName, hostVersionRange) ->
        // Find the host module in the project that matches the Fragment-Host symbolic name + version range
        val hostModule = project.allPDEModules(area).find { candidate ->
          val man = cacheService.getManifest(candidate)
          val bsn = man?.bundleSymbolicName?.key
          val ver = man?.bundleVersion

          bsn == hostSymbolicName && (ver in hostVersionRange)
        }
        if (hostModule != null) {
          // 2) Also inherit the host’s direct dependencies (modules & libraries)
          // This merges the host's classpath entries into the fragment.
          val hostOrderEntries = hostModule.moduleRootManager.orderEntries
          // Inherit host’s module dependencies
          hostOrderEntries.filterIsInstance<ModuleOrderEntry>().forEach { entry ->
            entry.module?.let { depModule ->
              // Prevent adding the same module as a dependency to itself
              if (depModule != area) {
                addModuleDependency(depModule)
              }
            }
          }
          // Inherit host’s library dependencies
          hostOrderEntries.filterIsInstance<LibraryOrderEntry>().forEach { entry ->
            entry.library?.let { lib ->
              model.addLibraryEntry(lib)
            }
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
      val needed = resolvedBundles.union(imported)

      // add module dependency if there is such a module in the project
      project.allPDEModules(area).filter { module ->
        val manifest = cacheService.getManifest(module)
        val bsn = manifest?.bundleSymbolicName?.key
        needed.contains(bsn)
      }.forEach {
        model.addModuleOrderEntry(it)
      }

      val libraryWhitelist: Set<String> = setOf(
        "org.eclipse.jdt.annotation",
        "org.eclipse.io"
      )

      val pdeModuleNames = project.allPDEModules(area).map { t -> t.name }.toSet()
      project.libraryTable().libraries
        // only consider "Partial: " libraries
        .filter { it.name?.startsWith(ProjectLibraryNamePrefix) == true }
        .filter { bsnOfLibrary(it) in needed
            || bsnOfLibrary(it) in libraryWhitelist
            // e.g. org.eclipse.swt.gtk.linux.x86_64
            || bsnOfLibrary(it)?.startsWith("org.eclipse.swt.") == true
        }
        // do not add libraries for bundles that are already represented by modules in project
        .filter { bsnOfLibrary(it) !in pdeModuleNames }
        .forEach { depLibrary ->
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
