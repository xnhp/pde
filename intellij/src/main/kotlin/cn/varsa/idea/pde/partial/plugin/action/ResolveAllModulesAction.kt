package cn.varsa.idea.pde.partial.plugin.action

import cn.varsa.idea.pde.partial.plugin.facet.*
import cn.varsa.idea.pde.partial.plugin.helper.*
import cn.varsa.idea.pde.partial.plugin.i18n.EclipsePDEPartialBundles
import cn.varsa.idea.pde.partial.plugin.openapi.resolver.*
import cn.varsa.idea.pde.partial.plugin.support.*
import com.intellij.openapi.actionSystem.*
import com.intellij.openapi.module.*
import com.intellij.openapi.progress.*
import com.intellij.openapi.project.*
import com.intellij.openapi.ui.*

// see also ResolveManifestAction, does the same thing triggered from MANIFEST.MF files
class ResolveAllModulesAction : AnAction() {

  override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT


  override fun actionPerformed(e: AnActionEvent) {
    val project: Project? = e.project
    if (project == null) {
      Messages.showMessageDialog(project, "Invalid event parameters", "${project}", Messages.getErrorIcon())
      return
    }
    project.allPDEModules().forEach { module -> resolveModule(project, module)
    }

  }

  override fun update(e: AnActionEvent) {
    e.presentation.apply {
      text = EclipsePDEPartialBundles.message("action.resolveAllModules.text")
      isEnabledAndVisible = e.project?.run {
        moduleFromEvent(e)?.let { PDEFacet.getInstance(it) != null }
      } == true
    }
  }
}

