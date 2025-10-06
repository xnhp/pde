package cn.varsa.idea.pde.partial.plugin.launch

import cn.varsa.idea.pde.partial.common.service.ConfigService
import cn.varsa.pde.resolver.algo.Resolver
import cn.varsa.pde.resolver.algo.ResolveOptions
import cn.varsa.pde.resolver.algo.WorkspaceBundleDescriptor
import cn.varsa.pde.resolver.index.TargetPlatformIndex
import cn.varsa.pde.resolver.launch.BundleStartSpec
import cn.varsa.pde.resolver.launch.LaunchContext
import cn.varsa.pde.resolver.launch.LauncherOptions
import cn.varsa.pde.resolver.launch.LauncherPlan
import java.io.File
import org.osgi.framework.Version

object LauncherPlanBuilder {
  data class PlanResult(val plan: LauncherPlan, val context: LaunchContext)

  fun build(
    configService: ConfigService,
    options: LauncherOptions,
    targetIndex: TargetPlatformIndex,
    workspaceDescriptors: List<WorkspaceBundleDescriptor>,
    startupBundles: Map<String, Int>
  ): PlanResult {
    val startupLevels = mutableMapOf<String, Int>()
    val bundleMap = LinkedHashMap<String, BundleStartSpec>()

    fun registerBundle(bsn: String, version: Version, path: java.nio.file.Path, isWorkspace: Boolean) {
      val level = configService.startUpLevel(bsn)
      startupLevels[bsn] = level
      val spec = BundleStartSpec(
        bsn = bsn,
        version = version,
        location = path,
        startLevel = level,
        autoStart = configService.isAutoStartUp(bsn),
        isWorkspace = isWorkspace
      )
      val existing = bundleMap[bsn]
      if (existing == null || (spec.isWorkspace && !existing.isWorkspace)) {
        bundleMap[bsn] = spec
      }
    }

    val resolveOptions = ResolveOptions(preferWorkspace = true, includeHostsForFragments = true)

    val roots = mutableListOf<Pair<WorkspaceBundleDescriptor, Boolean>>()
    roots += workspaceDescriptors.map { it to true }

    val targetRoots = startupBundles.keys
      .mapNotNull { bsn -> targetIndex.bundlesByBsn()[bsn]?.lastEntry()?.value }
      .map { WorkspaceBundleDescriptor(it.location, it.manifest) to false }

    if (roots.isEmpty() && targetRoots.isEmpty()) {
      // fallback: include highest versions of all target bundles
      roots += targetIndex.bundlesByBsn().values.mapNotNull { it.lastEntry()?.value }
        .map { WorkspaceBundleDescriptor(it.location, it.manifest) to false }
    } else {
      roots += targetRoots
    }

    val workspaceList = workspaceDescriptors

    roots.forEach { (entry, isWorkspaceEntry) ->
      val bsn = entry.manifest.bundleSymbolicName?.key ?: return@forEach
      registerBundle(bsn, entry.manifest.bundleVersion, entry.path, isWorkspaceEntry)
      val result = Resolver.resolve(targetIndex, workspaceList, entry, resolveOptions)
      result.bundles.forEach { rb -> registerBundle(rb.bsn, rb.version, rb.path, rb.isWorkspace) }
    }

    val bundles = bundleMap.values.toList()
    val plan = LauncherPlan(
      bundles = bundles,
      framework = bundleMap[options.frameworkBSN]
    )
    val context = LaunchContext(
      startupLevels = startupLevels,
      devProperties = configService.devModules.associate { it.bundleSymbolicName to it.compilerClassRelativePathToModule }
    )
    return PlanResult(plan, context)
  }
}
