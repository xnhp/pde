package cn.varsa.idea.pde.partial.plugin.action

import cn.varsa.idea.pde.partial.common.*
import cn.varsa.idea.pde.partial.plugin.facet.*
import cn.varsa.idea.pde.partial.plugin.i18n.*
import cn.varsa.idea.pde.partial.plugin.support.*
import com.intellij.openapi.actionSystem.*

class ResolveManifestAction : AnAction() {

  override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT
  override fun update(e: AnActionEvent) {
    e.presentation.apply {
      text = EclipsePDEPartialBundles.message("action.resolveModule.text")
      isEnabledAndVisible = e.project?.run {
        e.getData(CommonDataKeys.VIRTUAL_FILE)
          ?.takeIf { it.isInLocalFileSystem && (it.name == ManifestMf || it.name == BuildProperties) }?.findModule(this)
          ?.let { PDEFacet.getInstance(it) != null }
      } == true
    }
  }

  override fun actionPerformed(e: AnActionEvent) {
    e.project?.run {
      e.getData(CommonDataKeys.VIRTUAL_FILE)
        ?.takeIf { it.isInLocalFileSystem && (it.name == ManifestMf || it.name == BuildProperties) }?.findModule(this)
        ?.let { resolveModule(this, it) }
    }
  }
}
