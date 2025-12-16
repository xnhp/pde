package cn.varsa.pde.resolver.cli.config

import cn.varsa.pde.resolver.launch.WorkspaceInputs
import cn.varsa.pde.resolver.workspace.WorkspaceModuleBuilder
import cn.varsa.pde.resolver.workspace.WorkspaceModuleDefinition

object WorkspaceModuleResolver {
  private val defaultClassRoots = listOf("build/classes/java/main", "out/production")

  fun resolve(context: LaunchConfigContext): WorkspaceInputs {
    val definitions = context.config.workspaceModules.map { module ->
      val modulePath = context.baseDir.resolve(module.path).normalize()
      val classRoots = module.classes?.takeIf { it.isNotEmpty() } ?: defaultClassRoots
      WorkspaceModuleDefinition(moduleDir = modulePath, classRoots = classRoots)
    }
    return WorkspaceModuleBuilder.build(definitions)
  }
}
