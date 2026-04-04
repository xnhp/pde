package cn.varsa.pde.resolver.cli.config

import cn.varsa.pde.resolver.launch.WorkspaceInputs
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
    return context.config.bundles.mapNotNull { bundle ->
      val path = Paths.get(bundle.path)
      val modulePath = (if (path.isAbsolute) path else context.workingDir.resolve(path)).toAbsolutePath().normalize()
      val normalized = modulePath.toString()
      if (!seen.add(normalized)) return@mapNotNull null
      WorkspaceModuleDefinition(moduleDir = modulePath, classRoots = bundle.classRoots)
    }
  }
}
