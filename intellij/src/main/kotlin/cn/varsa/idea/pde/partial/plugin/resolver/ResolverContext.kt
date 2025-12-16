package cn.varsa.idea.pde.partial.plugin.resolver

import cn.varsa.idea.pde.partial.plugin.cache.BundleManifestCacheService
import cn.varsa.idea.pde.partial.plugin.config.PreferenceService
import cn.varsa.idea.pde.partial.plugin.config.PluginTargetIndexService
import cn.varsa.idea.pde.partial.plugin.support.allPDEModules
import cn.varsa.pde.resolver.algo.ResolveOptions
import cn.varsa.pde.resolver.algo.WorkspaceBundleDescriptor
import cn.varsa.pde.resolver.workspace.WorkspaceBundleLoader
import com.intellij.openapi.module.Module
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ModuleRootManager
import com.intellij.openapi.vfs.VirtualFile
import java.nio.file.Paths

internal data class ResolverContext(
  val project: Project,
  val cache: BundleManifestCacheService,
  val tpService: PluginTargetIndexService,
  val moduleByBsn: Map<String, Module>,
  val workspace: List<WorkspaceBundleDescriptor>,
  val descriptorByModule: MutableMap<Module, WorkspaceBundleDescriptor>,
  val targetIndex: cn.varsa.pde.resolver.index.TargetPlatformIndex
)

internal fun buildResolverContext(
  project: Project,
  cache: BundleManifestCacheService
): ResolverContext {
  val tpService = PluginTargetIndexService.getInstance(project)
  val allPdeModules = project.allPDEModules()
  val moduleByBsn = allPdeModules.mapNotNull { module ->
    val manifest = cache.getManifest(module)
    val bsn = manifest?.bundleSymbolicName?.key
    if (bsn != null) bsn to module else null
  }.toMap()

  fun modulePath(module: Module): java.nio.file.Path? {
    val root: VirtualFile = ModuleRootManager.getInstance(module).contentRoots.firstOrNull() ?: return null
    return Paths.get(root.path)
  }

  val descriptorByModule = mutableMapOf<Module, WorkspaceBundleDescriptor>()
  val workspace = allPdeModules.mapNotNull { module ->
    val path = modulePath(module) ?: return@mapNotNull null
    val descriptor = runCatching { WorkspaceBundleLoader.load(path) }
      .getOrElse { cache.getManifest(module)?.let { WorkspaceBundleDescriptor(path, it) } }
    descriptor?.also { descriptorByModule[module] = it }
  }
  val targetIndex = tpService.getIndex()

  return ResolverContext(project, cache, tpService, moduleByBsn, workspace, descriptorByModule, targetIndex)
}

internal fun ResolverContext.workspaceEntry(module: Module): WorkspaceBundleDescriptor? {
  descriptorByModule[module]?.let { return it }
  val root = ModuleRootManager.getInstance(module).contentRoots.firstOrNull() ?: return null
  val path = Paths.get(root.path)
  val descriptor = runCatching { WorkspaceBundleLoader.load(path) }
    .getOrElse { cache.getManifest(module)?.let { WorkspaceBundleDescriptor(path, it) } }
  descriptor?.let { descriptorByModule[module] = it }
  return descriptor
}

internal fun ResolverContext.options(includeHosts: Boolean): ResolveOptions = ResolveOptions(
  whitelistPrefixes = PreferenceService.getInstance(project).libraryWhitelist,
  preferWorkspace = true,
  includeHostsForFragments = includeHosts
)
