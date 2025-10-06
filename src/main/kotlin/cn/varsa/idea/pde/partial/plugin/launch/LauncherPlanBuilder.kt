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
    workspaceDescriptors: List<WorkspaceBundleDescriptor>
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

    if (workspaceDescriptors.isNotEmpty()) {
      workspaceDescriptors.forEach { desc ->
        val bsn = desc.manifest.bundleSymbolicName?.key ?: return@forEach
        registerBundle(bsn, desc.manifest.bundleVersion, desc.path, true)
        val result = Resolver.resolve(targetIndex, workspaceDescriptors, desc, resolveOptions)
        result.bundles.forEach { rb ->
          registerBundle(rb.bsn, rb.version, rb.path, rb.isWorkspace)
        }
      }
    } else {
      configService.libraries.forEach { file ->
        val manifest = configService.getManifest(file) ?: return@forEach
        val bsn = manifest.bundleSymbolicName?.key ?: return@forEach
        registerBundle(bsn, manifest.bundleVersion, file.toPath(), false)
      }
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
