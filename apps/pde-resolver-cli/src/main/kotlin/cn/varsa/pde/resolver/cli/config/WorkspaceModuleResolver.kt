package cn.varsa.pde.resolver.cli.config

import cn.varsa.pde.resolver.launch.WorkspaceInputs
import cn.varsa.pde.resolver.workspace.WorkspaceDefaults
import cn.varsa.pde.resolver.workspace.WorkspaceModuleBuilder
import cn.varsa.pde.resolver.workspace.WorkspaceModuleDefinition
import java.nio.file.Files
import java.nio.file.Paths
import java.util.logging.Logger

object WorkspaceModuleResolver {
  private val logger = Logger.getLogger(WorkspaceModuleResolver::class.java.name)

  fun resolve(context: LaunchConfigContext, allowMissingClasses: Boolean = false): WorkspaceInputs {
    val definitions = resolveDefinitions(context)
    val filteredDefinitions = definitions.filter { definition ->
      val moduleDir = definition.moduleDir
      val manifestPath = moduleDir.resolve("META-INF").resolve("MANIFEST.MF")
      if (!Files.exists(manifestPath)) {
        logger.warning("Skipping workspace module at $moduleDir: missing META-INF/MANIFEST.MF")
        false
      } else {
        true
      }
    }
    return WorkspaceModuleBuilder.build(filteredDefinitions, allowMissingClasses)
  }

  fun resolveDefinitions(context: LaunchConfigContext): List<WorkspaceModuleDefinition> {
    val seen = linkedSetOf<String>()
    return context.config.bundlesPerRepo.flatMap { repoEntry ->
      val skipBundles = (context.config.nonPdeBundles + repoEntry.nonPdeBundles).toSet()
      val repoPath = Paths.get(repoEntry.repo)
      val repoBase = if (repoPath.isAbsolute) repoPath else context.workingDir.resolve(repoPath)
      repoEntry.bundles.mapNotNull { bundle ->
        if (skipBundles.contains(bundle.name)) return@mapNotNull null
        val modulePath = repoBase.resolve(bundle.name).toAbsolutePath().normalize()
        val normalized = modulePath.toString()
        if (!seen.add(normalized)) return@mapNotNull null
        val classRoots = bundle.classes?.takeIf { it.isNotEmpty() } ?: WorkspaceDefaults.DEFAULT_CLASS_ROOTS
        WorkspaceModuleDefinition(moduleDir = modulePath, classRoots = classRoots)
      }
    }
  }
}
