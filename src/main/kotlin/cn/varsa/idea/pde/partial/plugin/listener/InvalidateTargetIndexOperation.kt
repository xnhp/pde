package cn.varsa.idea.pde.partial.plugin.listener

import cn.varsa.idea.pde.partial.plugin.config.PluginTargetIndexService
import com.intellij.openapi.project.Project

class InvalidateTargetIndexOperation : TargetDefinitionChangeListener {
  override fun locationsChanged(project: Project) {
    // Clear cached TargetPlatformIndex so next resolve uses fresh roots
    PluginTargetIndexService.getInstance(project).invalidate()
  }
}

