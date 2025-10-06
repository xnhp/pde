package cn.varsa.idea.pde.partial.plugin.launch

import cn.varsa.idea.pde.partial.common.service.ConfigService
import cn.varsa.pde.resolver.launch.BundleStartSpec
import cn.varsa.pde.resolver.launch.LaunchContext
import cn.varsa.pde.resolver.launch.LauncherOptions
import cn.varsa.pde.resolver.launch.LauncherPlan
import java.io.File
import org.osgi.framework.Version

object LauncherPlanBuilder {
  data class PlanResult(val plan: LauncherPlan, val context: LaunchContext)

  fun build(configService: ConfigService, options: LauncherOptions): PlanResult {
    val startupLevels = mutableMapOf<String, Int>()
    val bundles = mutableListOf<BundleStartSpec>()

    fun registerBundle(bsn: String, version: Version, file: File, isWorkspace: Boolean) {
      val level = configService.startUpLevel(bsn)
      startupLevels[bsn] = level
      bundles += BundleStartSpec(
        bsn = bsn,
        version = version,
        location = file.toPath(),
        startLevel = level,
        autoStart = configService.isAutoStartUp(bsn),
        isWorkspace = isWorkspace
      )
    }

    configService.libraries.forEach { file ->
      val manifest = configService.getManifest(file) ?: return@forEach
      val bsn = manifest.bundleSymbolicName?.key ?: return@forEach
      registerBundle(bsn, manifest.bundleVersion, file, false)
    }

    configService.devModules.forEach { module ->
      val moduleDir = File(configService.projectDirectory, module.relativePathToProject)
      val manifest = configService.getManifest(moduleDir)
      val bsn = manifest?.bundleSymbolicName?.key ?: module.bundleSymbolicName
      val version = manifest?.bundleVersion ?: Version.emptyVersion
      registerBundle(bsn, version, moduleDir, true)
    }

    val sorted = bundles.sortedBy { it.bsn }
    val plan = LauncherPlan(
      bundles = sorted,
      framework = sorted.firstOrNull { it.bsn == options.frameworkBSN }
    )
    val context = LaunchContext(
      startupLevels = startupLevels,
      devProperties = configService.devModules.associate { it.bundleSymbolicName to it.compilerClassRelativePathToModule }
    )
    return PlanResult(plan, context)
  }
}
