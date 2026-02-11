package cn.varsa.pde.resolver.cli.config

import cn.varsa.pde.resolver.launch.WorkspaceInputs
import cn.varsa.pde.resolver.workspace.WorkspaceDefaults
import cn.varsa.pde.resolver.workspace.WorkspaceModuleBuilder
import cn.varsa.pde.resolver.workspace.WorkspaceModuleDefinition
import java.nio.file.Files
import java.util.logging.Logger

object WorkspaceModuleResolver {
  private val logger = Logger.getLogger(WorkspaceModuleResolver::class.java.name)

  fun resolve(context: LaunchConfigContext, allowMissingClasses: Boolean = false): WorkspaceInputs {
    val definitions = context.config.workspaceModules.map { module ->
      val modulePath = context.baseDir.resolve(module.path).normalize()
      val classRoots = module.classes?.takeIf { it.isNotEmpty() } ?: WorkspaceDefaults.DEFAULT_CLASS_ROOTS
      WorkspaceModuleDefinition(moduleDir = modulePath, classRoots = classRoots)
    }
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
}
