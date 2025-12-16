package cn.varsa.idea.pde.partial.plugin.resolver

import cn.varsa.idea.pde.partial.common.KotlinOrderEntryName
import cn.varsa.idea.pde.partial.plugin.cache.BundleManifestCacheService
import cn.varsa.idea.pde.partial.plugin.config.ResolveSessionService
import cn.varsa.idea.pde.partial.plugin.facet.PDEFacet
import cn.varsa.idea.pde.partial.plugin.i18n.EclipsePDEPartialBundles
import cn.varsa.idea.pde.partial.plugin.openapi.resolver.ManifestLibraryResolver
import cn.varsa.idea.pde.partial.plugin.support.*
import cn.varsa.pde.resolver.algo.Resolver
import com.intellij.openapi.module.Module
import com.intellij.openapi.roots.DependencyScope
import com.intellij.openapi.roots.JdkOrderEntry
import com.intellij.openapi.roots.ModuleOrderEntry
import com.intellij.openapi.roots.ModuleSourceOrderEntry
import com.intellij.openapi.roots.OrderEntry

class PdeModuleFragmentLibraryResolver : ManifestLibraryResolver {
  override val displayName: String = EclipsePDEPartialBundles.message("resolver.pde.moduleFragment")

  override fun resolve(area: Module) {
    PDEFacet.getInstance(area) ?: return

    val project = area.project
    val cacheService = BundleManifestCacheService.getInstance(project)
    cacheService.getManifest(area) ?: return

    val ctx = buildResolverContext(project, cacheService)
    val entryDesc = ctx.workspaceEntry(area) ?: return

    val session = ResolveSessionService.getInstance(project)
    val cached = session.get(area) ?: session.get(entryDesc)
    val result = cached ?: Resolver.resolve(ctx.targetIndex, ctx.workspace, entryDesc, ctx.options(includeHosts = true))
    if (cached == null) session.put(area, entryDesc, result)

    notifyResolverProblems(project, area.name, result)

    area.updateModel { model ->
      result.moduleDependencies.forEach { bsn ->
        val dependencyModule = ctx.moduleByBsn[bsn] ?: return@forEach
        if (dependencyModule == area) return@forEach
        val entry = model.findModuleOrderEntry(dependencyModule) ?: model.addModuleOrderEntry(dependencyModule)
        entry.scope = DependencyScope.COMPILE
        entry.isExported = true
      }
    }
  }

  override fun postResolve(area: Module) {
    PDEFacet.getInstance(area) ?: return

    val project = area.project
    val cacheService = BundleManifestCacheService.getInstance(project)
    val ctx = buildResolverContext(project, cacheService)
    val entryDesc = ctx.workspaceEntry(area) ?: return

    val session = ResolveSessionService.getInstance(project)
    val cached = session.get(area) ?: session.get(entryDesc)
    val result = cached ?: Resolver.resolve(ctx.targetIndex, ctx.workspace, entryDesc, ctx.options(includeHosts = true))

    area.updateModel { model ->
      val orderEntries = model.orderEntries.toMutableList()
      val orderEntriesMap = orderEntries.associateBy { it.presentableName }
      val dependencyOrder = dependencyOrderFrom(result, orderEntriesMap)
      if (dependencyOrder.isEmpty()) return@updateModel

      val anchor: (OrderEntry) -> Boolean = { entry ->
        entry is JdkOrderEntry || entry is ModuleSourceOrderEntry || entry.presentableName.startsWith(KotlinOrderEntryName)
      }

      val initialAnchor = orderEntries.indexOfLast(anchor) + 1

      val arranged = orderEntries.apply {
        removeAll(dependencyOrder)
        val anchorIndex = (indexOfLast(anchor) + 1).let { idx -> if (idx <= 0) initialAnchor else idx }
          .coerceAtMost(size)
        addAll(anchorIndex, dependencyOrder)
      }.toTypedArray()

      model.rearrangeOrderEntries(arranged)
    }
  }
}
