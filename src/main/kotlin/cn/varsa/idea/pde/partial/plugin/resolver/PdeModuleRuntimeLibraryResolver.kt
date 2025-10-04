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
import cn.varsa.pde.resolver.algo.*
import cn.varsa.pde.resolver.manifest.canonicalName
import com.intellij.openapi.module.*
import com.intellij.openapi.roots.*
import com.intellij.openapi.roots.libraries.*
import org.osgi.framework.Version

class PdeModuleRuntimeLibraryResolver : ManifestLibraryResolver {
  override val displayName: String = message("resolver.pde.moduleRuntime")

  override fun preResolve(area: Module) {
    PDEFacet.getInstance(area) ?: return

    area.updateModel { model ->
      val modulesToRemove = mutableListOf<Module>()
      val librariesToRemove = mutableListOf<Library>()

      // Snapshot candidates but remove by re-finding entries in this model to avoid bridge mismatch
      model.orderEntries.forEach { entry ->
        when (entry) {
          is ModuleOrderEntry -> entry.module?.let { modulesToRemove += it }
          is LibraryOrderEntry -> {
            val lib = entry.library
            val name = lib?.name
            if (lib != null && name != null &&
              (name.startsWith(ProjectLibraryNamePrefix) || name == ModuleLibraryName)
            ) librariesToRemove += lib
          }
        }
      }

      modulesToRemove.forEach { m ->
        model.findModuleOrderEntry(m)?.let { model.removeOrderEntry(it) }
      }
      librariesToRemove.forEach { lib ->
        model.findLibraryOrderEntry(lib)?.let { model.removeOrderEntry(it) }
      }
    }
  }

  // TODO: consider module-scoped action when needed
  override fun resolve(area: Module) {
    PDEFacet.getInstance(area) ?: return

    val project = area.project
    val cacheService = BundleManifestCacheService.getInstance(project)
    val bundleManifest = cacheService.getManifest(area) ?: return

    val classesRoot = bundleManifest.bundleClassPath?.keys?.filterNot { it == "." }?.flatMap { binaryName ->
      area.moduleRootManager.contentRoots.mapNotNull { it.findFileByRelativePath(binaryName) }
    }?.map { it.protocolUrl }?.distinct() ?: emptyList()

    area.updateModel { model ->
      // 1) Ensure module-level runtime library reflects Bundle-ClassPath
      ensureModuleRuntimeLibrary(model, classesRoot)

      // 2) Resolve dependencies via core resolver
      val ctx = buildContext(project, cacheService)
      val entryDesc = ctx.workspaceEntry(area) ?: return@updateModel
      val result = Resolver.resolve(ctx.targetIndex, ctx.workspace, entryDesc, ctx.options(includeHosts = true))

      // 3) Mirror workspace host deps (modules + project-level libs)
      val hostLibs = mirrorHostDependencies(area, model, ctx, result)

      // 4) Add resolved target libraries (create lazily in project table)
      addResolvedLibraries(model, ctx, result, hostLibs)
    }
  }

  override fun postResolve(area: Module) {
    PDEFacet.getInstance(area) ?: return

    val project = area.project
    val cacheService = BundleManifestCacheService.getInstance(project)
    val manifest = cacheService.getManifest(area) ?: return
    val hostBCN = project.fragmentHostManifest(manifest, area)?.canonicalName

    area.updateModel { model ->
      // Reuse cached index/workspace build and recompute ordering
      val ctx = buildContext(project, cacheService)
      val entryDesc = ctx.workspaceEntry(area) ?: return@updateModel
      val orderingResult = Resolver.resolve(ctx.targetIndex, ctx.workspace, entryDesc, ctx.options(includeHosts = true))

      val orderEntries = model.orderEntries.toMutableList()
      val orderEntriesMap = orderEntries.associateBy { it.presentableName }

      val kotlinOrder = orderEntriesMap.filter { it.key.startsWith(KotlinOrderEntryName) }.values.toSet()
      val runtimeOrder = orderEntriesMap[ModuleLibraryName]
      val hostOrder = orderEntriesMap[hostBCN] ?: orderEntriesMap["$ProjectLibraryNamePrefix$hostBCN"]
      // Use core resolver's result order for dependency libraries
      val dependencyOrder = dependencyOrderFrom(orderingResult, orderEntriesMap)

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

  // Helpers

  private data class Context(
    val project: com.intellij.openapi.project.Project,
    val cache: BundleManifestCacheService,
    val tpService: PluginTargetIndexService,
    val moduleByBsn: Map<String, Module>,
    val workspace: List<WorkspaceBundleDescriptor>,
    val targetIndex: cn.varsa.pde.resolver.index.TargetPlatformIndex
  )

  private fun buildContext(project: com.intellij.openapi.project.Project, cache: BundleManifestCacheService): Context {
    val tpService = PluginTargetIndexService.getInstance(project)
    val allPdeModules = project.allPDEModules()
    val moduleByBsn = allPdeModules.mapNotNull { m ->
      val man = cache.getManifest(m)
      val bsn = man?.bundleSymbolicName?.key
      if (bsn != null) bsn to m else null
    }.toMap()
    fun modulePath(m: Module) = m.moduleRootManager.contentRoots.firstOrNull()?.path?.let { java.nio.file.Paths.get(it) }
    val workspace = allPdeModules.mapNotNull { m ->
      val man = cache.getManifest(m) ?: return@mapNotNull null
      val path = modulePath(m) ?: return@mapNotNull null
      WorkspaceBundleDescriptor(path, man)
    }
    val targetIndex = tpService.getIndex()
    return Context(project, cache, tpService, moduleByBsn, workspace, targetIndex)
  }

  private fun Context.workspaceEntry(area: Module): WorkspaceBundleDescriptor? {
    val path = area.moduleRootManager.contentRoots.firstOrNull()?.path?.let { java.nio.file.Paths.get(it) } ?: return null
    val man = cache.getManifest(area) ?: return null
    return WorkspaceBundleDescriptor(path, man)
  }

  private fun Context.options(includeHosts: Boolean) = ResolveOptions(
    whitelistPrefixes = PreferenceService.getInstance(project).libraryWhitelist,
    preferWorkspace = true,
    includeHostsForFragments = includeHosts
  )

  private fun ensureModuleRuntimeLibrary(model: ModifiableRootModel, classesRoot: List<String>) {
    val libraryTableModel = model.moduleLibraryTable.modifiableModel
    applicationInvokeAndWait {
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
  }

  private fun dependencyOrderFrom(
    result: cn.varsa.pde.resolver.algo.ResolveResult,
    map: Map<String, OrderEntry>
  ): List<OrderEntry> = result.bundles.filter { !it.isWorkspace }.map { rb ->
    val dash = "${rb.bsn}-${rb.version}"
    val at = "$ProjectLibraryNamePrefix${rb.bsn}${BundleDefinition.canonicalNameSeparator}${rb.version}"
    listOf(dash, "$ProjectLibraryNamePrefix$dash", at)
  }.flatten().mapNotNull { map[it] }

  private fun mirrorHostDependencies(
    area: Module,
    model: ModifiableRootModel,
    ctx: Context,
    result: cn.varsa.pde.resolver.algo.ResolveResult
  ): List<Library> {
    fun addModuleDependency(moduleToAdd: Module): ModuleOrderEntry =
      model.findModuleOrderEntry(moduleToAdd) ?: model.addModuleOrderEntry(moduleToAdd)

    val hostWorkspaceBsn = result.bundles.firstOrNull { it.isHost && it.isWorkspace }?.bsn
    val out = mutableListOf<Library>()
    if (hostWorkspaceBsn != null) {
      val hostModule = ctx.moduleByBsn[hostWorkspaceBsn]
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
        // Only inherit project-level libraries from host; skip module-level ones
        hostOrderEntries.filterIsInstance<LibraryOrderEntry>().forEach { entry ->
          val lib = entry.library ?: return@forEach
          val name = lib.name
          val isProjectLevel = lib.table != null
          val isModuleSpecial = name == ModuleLibraryName || name == ModuleCompileOnlyLibraryName
          if (isProjectLevel && !isModuleSpecial) out += lib
        }
      }
    }
    // Also add workspace-modules from resolver result
    result.bundles.filter { it.isWorkspace }.forEach { rb ->
      val mod = ctx.moduleByBsn[rb.bsn]
      if (mod != null && mod != area) addModuleDependency(mod)
    }
    return out
  }

  private fun addResolvedLibraries(
    model: ModifiableRootModel,
    ctx: Context,
    result: cn.varsa.pde.resolver.algo.ResolveResult,
    hostLibraries: List<Library>
  ) {
    val libsByBsn = projectLibrariesIndex(ctx.project)
    val addedLibs = hashSetOf<Library>()

    fun ensureProjectLibrary(bsn: String, ver: Version): Library? {
      val existing = libsByBsn[bsn]?.get(ver)
      if (existing != null) return existing
      val rb = ctx.targetIndex.bundlesByBsn()[bsn]?.get(ver) ?: return null
      val libraryName = "$ProjectLibraryNamePrefix$bsn${BundleDefinition.canonicalNameSeparator}$ver"
      var created: Library? = null
      applicationInvokeAndWait {
        val projTableModel = ctx.project.libraryTable().modifiableModel
        val lib = writeCompute { projTableModel.createLibrary(libraryName) }
        val libModel = lib.modifiableModel
        val local = com.intellij.openapi.vfs.LocalFileSystem.getInstance()
        val jarfs = com.intellij.openapi.vfs.JarFileSystem.getInstance()
        if (rb.isDirectory) {
          val dirVf = local.refreshAndFindFileByNioFile(rb.location)
          if (dirVf != null) {
            libModel.addRoot(dirVf.url, OrderRootType.CLASSES)
            val bcp = rb.manifest.bundleClassPath?.keys ?: emptySet()
            bcp.filter { it != "." }.forEach { rel ->
              val child = dirVf.findFileByRelativePath(rel)
              if (child != null) {
                val root = if (child.fileSystem === jarfs) child else jarfs.getJarRootForLocalFile(child)
                if (root != null) libModel.addRoot(root.url, OrderRootType.CLASSES)
              }
            }
          }
        } else {
          val jarVf = local.refreshAndFindFileByNioFile(rb.location)
          if (jarVf != null) {
            val root = jarfs.getJarRootForLocalFile(jarVf)
            if (root != null) libModel.addRoot(root.url, OrderRootType.CLASSES)
          }
        }
        writeRun {
          libModel.commit()
          projTableModel.commit()
        }
        created = lib
      }
      created?.let { libsByBsn.computeIfAbsent(bsn) { java.util.TreeMap() }[ver] = it }
      return created
    }

    fun chooseLib(bsn: String, ver: Version): Library? {
      val nav = libsByBsn[bsn]
      val exact = nav?.get(ver)
      if (exact != null) return exact
      val created = ensureProjectLibrary(bsn, ver)
      if (created != null) return created
      return nav?.lastEntry()?.value
    }

    result.bundles.filter { !it.isWorkspace }.forEach { rb ->
      val lib = chooseLib(rb.bsn, rb.version)
      if (lib != null && addedLibs.add(lib)) model.addLibraryEntry(lib)
    }
    hostLibraries.forEach { lib -> if (addedLibs.add(lib)) model.addLibraryEntry(lib) }
  }

  private fun projectLibrariesIndex(project: com.intellij.openapi.project.Project):
    MutableMap<String, java.util.NavigableMap<Version, Library>> {
    // Prefer central index if available
    val index = ProjectLibraryIndexService.getInstance(project).getIndex()
    if (index.isNotEmpty()) return index.mapValues { java.util.TreeMap(it.value) }.toMutableMap()
    // Fallback: rebuild view from project libraries
    fun bsnOfLibraryName(name: String): String =
      name.substringAfterLast(ProjectLibraryNamePrefix).substringBeforeLast(BundleDefinition.canonicalNameSeparator)
    val tableLibs = project.libraryTable().libraries
    val map = hashMapOf<String, java.util.NavigableMap<Version, Library>>()
    tableLibs.filter { it.name?.startsWith(ProjectLibraryNamePrefix) == true }.forEach { lib ->
      val name = lib.name ?: return@forEach
      val bsn = bsnOfLibraryName(name)
      val verText = name.substringAfterLast(BundleDefinition.canonicalNameSeparator)
      val ver = try { Version.parseVersion(verText) } catch (_: Exception) { null }
      if (ver != null) map.computeIfAbsent(bsn) { java.util.TreeMap() }[ver] = lib
    }
    return map
  }
}
