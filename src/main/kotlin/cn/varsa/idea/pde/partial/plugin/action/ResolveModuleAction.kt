package cn.varsa.idea.pde.partial.plugin.action

import cn.varsa.idea.pde.partial.plugin.facet.*
import cn.varsa.idea.pde.partial.plugin.helper.*
import cn.varsa.idea.pde.partial.plugin.openapi.resolver.*
import cn.varsa.idea.pde.partial.plugin.support.*
import com.intellij.openapi.actionSystem.*
import com.intellij.openapi.module.*
import com.intellij.openapi.progress.*
import com.intellij.openapi.project.*
import com.intellij.openapi.ui.*

// see also ResolveManifestAction, does the same thing triggered from MANIFEST.MF files
class ResolveModuleAction : AnAction() {

  override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT


  override fun actionPerformed(e: AnActionEvent) {

    val project: Project? = e.project
    val module: Module? = e.getData(LangDataKeys.MODULE)

    if (project == null || module == null) {
      Messages.showMessageDialog(project, "Invalid event parameters", "${project}, ${module}", Messages.getErrorIcon())
    }

    resolveModule(project, module)

  }

  override fun update(e: AnActionEvent) {
    e.presentation.apply {
      text = "Resolve Module"
      isEnabledAndVisible = e.project?.run {
        moduleFromEvent(e)?.let { PDEFacet.getInstance(it) != null }
      } == true
    }
  }
}

fun resolveModule(project: Project?, module: Module?) {
  project?.run {
    module?.also {
      ModuleHelper.resetCompileOutputPath(it)
      ModuleHelper.resetCompileArtifact(it)
      object : BackgroundResolvable {
        override fun resolve(project: Project, indicator: ProgressIndicator) {
          indicator.checkCanceled()
          indicator.text = "Resolving module ${it.name}"
          PdeLibraryResolverRegistry.instance.resolveModule(it, indicator)
        }
      }.backgroundResolve(this)
    }
  }
}

fun moduleFromEvent(e: AnActionEvent) : Module? {
  return e.getData(LangDataKeys.MODULE)
}
