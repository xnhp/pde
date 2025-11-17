package cn.varsa.idea.pde.partial.plugin.launch

import cn.varsa.idea.pde.partial.common.service.ConfigService
import cn.varsa.idea.pde.partial.plugin.config.PluginTargetIndexService
import cn.varsa.idea.pde.partial.plugin.config.PreferenceService
import cn.varsa.idea.pde.partial.plugin.config.ResolveSessionService
import cn.varsa.idea.pde.partial.plugin.config.TargetDefinitionService
import cn.varsa.idea.pde.partial.plugin.helper.PdeNotifier
import cn.varsa.idea.pde.partial.plugin.resolver.formatResolverProblems
import cn.varsa.pde.resolver.algo.ResolveOptions
import cn.varsa.pde.resolver.algo.ResolveResult
import cn.varsa.pde.resolver.algo.WorkspaceBundleDescriptor
import cn.varsa.pde.resolver.launch.LaunchContext
import cn.varsa.pde.resolver.launch.LaunchEnvironment
import cn.varsa.pde.resolver.launch.LaunchPlanner
import cn.varsa.pde.resolver.launch.LaunchResolveSession
import cn.varsa.pde.resolver.launch.LauncherOptions
import cn.varsa.pde.resolver.launch.LauncherPlan
import com.intellij.openapi.project.Project
import java.io.File

object LauncherPlanBuilder {
  data class PlanResult(val plan: LauncherPlan, val context: LaunchContext)

  fun build(project: Project, configService: ConfigService, options: LauncherOptions): PlanResult {
    val targetIndex = PluginTargetIndexService.getInstance(project).getIndex()
    val resolveSession = ResolveSessionService.getInstance(project)
    val workspaceDescriptors = configService.devModules.mapNotNull { dm ->
      val moduleDir = File(configService.projectDirectory, dm.relativePathToProject)
      val manifest = configService.getManifest(moduleDir) ?: return@mapNotNull null
      WorkspaceBundleDescriptor(moduleDir.toPath(), manifest)
    }

    val resolverOptions = ResolveOptions(
      whitelistPrefixes = PreferenceService.getInstance(project).libraryWhitelist,
      preferWorkspace = true,
      includeHostsForFragments = true
    )
    val libraryBundles = configService.libraries.mapNotNull { file ->
      val manifest = configService.getManifest(file) ?: return@mapNotNull null
      val bsn = manifest.bundleSymbolicName?.key ?: file.nameWithoutExtension
      LaunchEnvironment.SupplementalBundle(
        bsn = bsn,
        version = manifest.bundleVersion,
        location = file.toPath()
      )
    }

    val devProperties = configService.devModules.associate { it.bundleSymbolicName to it.compilerClassRelativePathToModule }
    val requiredStartupBundles = TargetDefinitionService.getInstance(project).startupLevels.keys.toSet()

    val environment = LaunchEnvironment(
      targetIndex = targetIndex,
      workspaceEntries = workspaceDescriptors,
      resolverOptions = resolverOptions,
      libraryBundles = libraryBundles,
      requiredStartupBundles = requiredStartupBundles,
      startLevelProvider = configService::startUpLevel,
      autoStartProvider = configService::isAutoStartUp,
      devProperties = devProperties
    )

    val planResult = LaunchPlanner.build(
      environment = environment,
      options = options,
      session = ServiceLaunchResolveSession(resolveSession)
    )

    if (planResult.problemsByScope.isNotEmpty()) {
      val message = planResult.problemsByScope.entries.joinToString(separator = "\n\n") { (scope, problems) ->
        formatResolverProblems(scope, problems)
      }
      PdeNotifier.important("PDE Resolver", message).notify(project)
    }

    return PlanResult(planResult.plan, planResult.context)
  }
}

private class ServiceLaunchResolveSession(
  private val delegate: ResolveSessionService
) : LaunchResolveSession {
  override fun get(entry: WorkspaceBundleDescriptor): ResolveResult? = delegate.get(entry)
  override fun put(entry: WorkspaceBundleDescriptor, result: ResolveResult) = delegate.put(entry, result)
}
