package cn.varsa.idea.pde.partial.plugin.launch

import cn.varsa.idea.pde.partial.common.service.ConfigService
import cn.varsa.idea.pde.partial.plugin.config.PluginTargetIndexService
import cn.varsa.idea.pde.partial.plugin.config.TargetDefinitionService
import cn.varsa.idea.pde.partial.plugin.config.PreferenceService
import cn.varsa.idea.pde.partial.plugin.helper.PdeNotifier
import cn.varsa.idea.pde.partial.plugin.config.ResolveSessionService
import cn.varsa.idea.pde.partial.plugin.resolver.collectProblems
import cn.varsa.idea.pde.partial.plugin.resolver.formatResolverProblems
import cn.varsa.pde.resolver.algo.ResolveOptions
import cn.varsa.pde.resolver.algo.ResolveProblem
import cn.varsa.pde.resolver.algo.ResolveProblemType
import cn.varsa.pde.resolver.algo.Resolver
import cn.varsa.pde.resolver.algo.WorkspaceBundleDescriptor
import cn.varsa.pde.resolver.launch.BundleStartSpec
import cn.varsa.pde.resolver.launch.LaunchContext
import cn.varsa.pde.resolver.launch.LauncherOptions
import cn.varsa.pde.resolver.launch.LauncherPlan
import java.io.File
import org.osgi.framework.Version
import com.intellij.openapi.project.Project

object LauncherPlanBuilder {
  data class PlanResult(val plan: LauncherPlan, val context: LaunchContext)

  fun build(project: Project, configService: ConfigService, options: LauncherOptions): PlanResult {
    val startupLevels = mutableMapOf<String, Int>()
    val bundleMap = linkedMapOf<String, MutableList<BundleStartSpec>>()

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
      val list = bundleMap.getOrPut(bsn) { mutableListOf() }
      if (isWorkspace) {
        // Workspace wins: drop all existing entries and keep only workspace
        list.clear()
        list += spec
      } else {
        // Skip if a workspace bundle already registered
        if (list.any { it.isWorkspace }) return
        // Avoid duplicates of same version/path
        if (list.none { it.location == spec.location || it.version == spec.version }) {
          list += spec
        }
      }
    }

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

    val problemsByScope = linkedMapOf<String, MutableList<ResolveProblem>>()

    fun recordProblems(scope: String, problems: List<ResolveProblem>) {
      if (problems.isEmpty()) return
      problemsByScope.getOrPut(scope) { mutableListOf() }.addAll(problems)
    }

    fun scopeName(entry: WorkspaceBundleDescriptor): String =
      entry.manifest.bundleSymbolicName?.key ?: entry.path.fileName?.toString() ?: entry.path.toString()

    workspaceDescriptors.forEach { entry ->
      val cached = resolveSession.get(entry)
      val result = cached ?: Resolver.resolve(targetIndex, workspaceDescriptors, entry, resolverOptions)
      if (cached == null) resolveSession.put(entry, result)
      recordProblems(scopeName(entry), result.collectProblems())
      result.bundles.forEach { rb ->
        registerBundle(rb.bsn, rb.version, rb.path, rb.isWorkspace)
      }
    }

    // Ensure target bundles from target platform index are present (full runtime image)
    configService.libraries.forEach { file ->
      val manifest = configService.getManifest(file) ?: return@forEach
      val bsn = manifest.bundleSymbolicName?.key ?: file.nameWithoutExtension
      registerBundle(bsn, manifest.bundleVersion, file.toPath(), false)
    }

    // Ensure startup-level bundles are present even if not in workspace closure
    TargetDefinitionService.getInstance(project).startupLevels.keys.forEach { startBsn ->
      if (!bundleMap.containsKey(startBsn)) {
        val rb = targetIndex.get(startBsn)
        if (rb != null) {
          registerBundle(startBsn, rb.manifest.bundleVersion, rb.location, false)
        } else {
          recordProblems("Launch Plan", listOf(
            ResolveProblem(
              type = ResolveProblemType.MISSING_BUNDLE,
              symbol = startBsn,
              message = "startup-level"
            )
          ))
        }
      }
    }

    val bundles = bundleMap.values.flatMap { it }
    val plan = LauncherPlan(
      bundles = bundles,
      framework = bundles.firstOrNull { it.bsn == options.frameworkBSN }
    )
    if (problemsByScope.isNotEmpty()) {
      val message = problemsByScope.entries.joinToString(separator = "\n\n") { (scope, problems) ->
        formatResolverProblems(scope, problems)
      }
      PdeNotifier.important("PDE Resolver", message).notify(project)
    }
    val context = LaunchContext(
      startupLevels = startupLevels,
      devProperties = configService.devModules.associate { it.bundleSymbolicName to it.compilerClassRelativePathToModule }
    )
    return PlanResult(plan, context)
  }
}
