package cn.varsa.idea.pde.partial.plugin.startup

import cn.varsa.idea.pde.partial.common.*
import cn.varsa.idea.pde.partial.plugin.facet.*
import cn.varsa.idea.pde.partial.plugin.helper.*
import cn.varsa.idea.pde.partial.plugin.i18n.EclipsePDEPartialBundles.message
import com.intellij.openapi.application.*
import com.intellij.openapi.compiler.*
import com.intellij.openapi.module.ModuleManager
import com.intellij.openapi.project.*
import com.intellij.openapi.roots.*
import com.intellij.psi.PsiErrorElement
import com.intellij.psi.PsiManager
import com.intellij.psi.util.*

object ManifestBuildGuard {
  fun install(project: Project) {
    val manager = CompilerManager.getInstance(project)
    if (manager.beforeTasks.any { it is ManifestBeforeCompileTask }) return

    manager.addBeforeTask(ManifestBeforeCompileTask(project, DefaultManifestProblemCollector, DefaultManifestProblemNotifier))
  }
}

internal class ManifestBeforeCompileTask(
  private val project: Project,
  private val problemCollector: ManifestProblemCollector,
  private val problemNotifier: ManifestProblemNotifier
) : CompileTask {
  override fun execute(context: CompileContext): Boolean {
    val problems = problemCollector.collect(project)
    if (problems.isEmpty()) return true

    notifyFailure(problems)
    report(context, problems)

    return false
  }

  private fun notifyFailure(problems: List<ManifestProblem>) {
    problemNotifier.notify(project, problems)
  }

  private fun report(context: CompileContext, problems: List<ManifestProblem>) {
    problems.forEach { problem ->
      val virtualFileUrl = problem.virtualFileUrl
      problem.errors.forEach { error ->
        context.addMessage(
          CompilerMessageCategory.ERROR,
          "[${problem.moduleName}] ${error.message}",
          virtualFileUrl,
          error.startOffset,
          error.endOffset
        )
      }
    }
  }
}

internal data class ManifestProblem(
  val moduleName: String,
  val virtualFileUrl: String?,
  val errors: List<ManifestError>
)

internal data class ManifestError(
  val message: String,
  val startOffset: Int,
  val endOffset: Int
)

internal fun interface ManifestProblemCollector {
  fun collect(project: Project): List<ManifestProblem>
}

internal fun interface ManifestProblemNotifier {
  fun notify(project: Project, problems: List<ManifestProblem>)
}

internal object DefaultManifestProblemCollector : ManifestProblemCollector {
  override fun collect(project: Project): List<ManifestProblem> = collectProblems(project)
}

internal object DefaultManifestProblemNotifier : ManifestProblemNotifier {
  override fun notify(project: Project, problems: List<ManifestProblem>) {
    val moduleNames = problems.joinToString { it.moduleName }
    PdeNotifier.important(
      message("manifest.build.guard.title"),
      message("manifest.build.guard.body", moduleNames)
    ).notify(project)
  }
}

internal fun collectProblems(project: Project): List<ManifestProblem> =
  ApplicationManager.getApplication().runReadAction<List<ManifestProblem>> {
    ModuleManager.getInstance(project).modules.asSequence()
      .filter { PDEFacet.getInstance(it) != null }
      .mapNotNull { module ->
        val psiManager = PsiManager.getInstance(project)
        val manifests = ModuleRootManager.getInstance(module).contentRoots.asSequence()
          .mapNotNull { it.findFileByRelativePath(ManifestPath) }
          .mapNotNull { psiManager.findFile(it) }

        val manifest = manifests.firstOrNull() ?: return@mapNotNull null
        val errors = PsiTreeUtil.collectElementsOfType(manifest, PsiErrorElement::class.java)
        if (errors.isEmpty()) {
          null
        } else {
          ManifestProblem(
            module.name,
            manifest.virtualFile?.url,
            errors.map { ManifestError(it.errorDescription, it.textOffset, it.textRange.endOffset) }
          )
        }
      }
      .toList()
  }
