package cn.varsa.idea.pde.partial.plugin.listener

import cn.varsa.idea.pde.partial.plugin.openapi.resolver.PdeLibraryResolverRegistry
import com.intellij.openapi.project.*

class ProjectLibraryResetOperation : TargetDefinitionChangeListener {
  override fun locationsChanged(project: Project) {
    PdeLibraryResolverRegistry.instance.resolveProjectAndModule(project)
  }
}
