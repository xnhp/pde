package cn.varsa.pde.resolver.cli.config

import cn.varsa.pde.resolver.launch.WorkspaceInputs
import cn.varsa.pde.resolver.workspace.WorkspaceDefaults
import cn.varsa.pde.resolver.workspace.WorkspaceModuleBuilder
import cn.varsa.pde.resolver.workspace.WorkspaceModuleDefinition

object WorkspaceModuleResolver {
  fun resolve(context: LaunchConfigContext, allowMissingClasses: Boolean = false): WorkspaceInputs {
    val definitions = context.config.workspaceModules.map { module ->
      val modulePath = context.baseDir.resolve(module.path).normalize()
      val classRoots = module.classes?.takeIf { it.isNotEmpty() } ?: WorkspaceDefaults.DEFAULT_CLASS_ROOTS
      WorkspaceModuleDefinition(moduleDir = modulePath, classRoots = classRoots)
    }
    return WorkspaceModuleBuilder.build(definitions, allowMissingClasses)
  }
}
