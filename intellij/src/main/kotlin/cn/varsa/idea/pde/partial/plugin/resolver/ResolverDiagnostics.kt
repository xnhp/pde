package cn.varsa.idea.pde.partial.plugin.resolver

import cn.varsa.idea.pde.partial.plugin.helper.PdeNotifier
import cn.varsa.pde.resolver.algo.ResolveProblem
import cn.varsa.pde.resolver.algo.ResolveProblemType
import cn.varsa.pde.resolver.algo.ResolveResult
import com.intellij.openapi.project.Project

internal fun ResolveResult.collectProblems(): List<ResolveProblem> =
  if (problems.isNotEmpty()) {
    problems
  } else {
    unresolved.map { unresolved ->
      ResolveProblem(
        type = ResolveProblemType.MISSING_BUNDLE,
        symbol = unresolved.bsn,
        range = unresolved.range,
        message = unresolved.reason
      )
    }
  }

internal fun formatResolverProblems(scope: String, problems: List<ResolveProblem>): String = buildString {
  append("Resolver issues for ")
  append(scope)
  append(':')
  problems.forEach { problem ->
    append("\n • ")
    append(problem.symbol)
    problem.range?.let { range ->
      append(' ')
      append(range)
    }
    append(" [")
    append(problem.type)
    append("] ")
    append(problem.message)
  }
}

internal fun notifyResolverProblems(project: Project, scope: String, result: ResolveResult) {
  val problems = result.collectProblems()
  if (problems.isEmpty()) return

  val message = formatResolverProblems(scope, problems)
  PdeNotifier.important("PDE Resolver", message).notify(project)
}
