package cn.varsa.pde.resolver.api

import org.eclipse.core.runtime.NullProgressMonitor
import org.eclipse.pde.api.tools.internal.builder.BaseApiAnalyzer
import org.eclipse.pde.api.tools.internal.builder.BuildContext
import org.eclipse.pde.api.tools.internal.model.ApiBaseline
import org.eclipse.pde.api.tools.internal.model.BundleComponent
import org.eclipse.pde.api.tools.internal.provisional.ApiPlugin
import org.eclipse.pde.api.tools.internal.provisional.model.IApiComponent
import org.eclipse.pde.api.tools.internal.provisional.problems.IApiProblem
import java.time.Clock
import java.time.Instant
import java.util.Properties

class DirectApiAnalyzerHarness(
  private val clock: Clock = Clock.systemUTC()
) {
  fun analyze(input: DirectApiAnalyzerInput): ApiAnalysisReport {
    val currentBaseline = ApiBaseline("current")
    val referenceBaseline = ApiBaseline("baseline")
    val analyzer = BaseApiAnalyzer()
    return try {
      val currentComponents = createComponents(currentBaseline, listOf(input.currentBundle) + input.dependencyArtifacts)
      val referenceComponents = createComponents(referenceBaseline, input.baselineArtifacts + input.dependencyArtifacts)
      currentBaseline.addApiComponents(currentComponents.toTypedArray())
      referenceBaseline.addApiComponents(referenceComponents.toTypedArray())

      val currentComponent = currentBaseline.getApiComponent(input.currentBundle.bundleSymbolicName)
        ?: error("Current bundle not found in analyzer baseline: ${input.currentBundle.bundleSymbolicName}")

      analyzer.setContinueOnResolverError(true)
      analyzer.analyzeComponent(
        null,
        null,
        input.preferences.toProperties(),
        referenceBaseline,
        currentComponent,
        BuildContext(),
        NullProgressMonitor()
      )

      val baselineComponent = referenceBaseline.getApiComponent(input.currentBundle.bundleSymbolicName)
      val report = ApiAnalysisReport(
        generatedAt = Instant.now(clock).toString(),
        tool = "pde api-analyze direct",
        problems = analyzer.getProblems().mapIndexed { index, problem ->
          problem.toReportProblem(index + 1, currentComponent, baselineComponent, input)
        }
      )
      input.outputReportPath.parent?.toFile()?.mkdirs()
      java.nio.file.Files.writeString(input.outputReportPath, ApiAnalysisReportJson.write(report))
      report
    } finally {
      analyzer.dispose()
      currentBaseline.dispose()
      referenceBaseline.dispose()
    }
  }

  private fun createComponents(baseline: ApiBaseline, artifacts: List<AnalyzerBundleArtifact>): List<BundleComponent> =
    artifacts.mapIndexed { index, artifact ->
      BundleComponent(baseline, artifact.path.toAbsolutePath().normalize().toString(), index.toLong() + 1).also { component ->
        require(component.isValidBundle) {
          "Analyzer artifact is not a valid OSGi bundle jar: ${artifact.path}"
        }
      }
    }

  private fun Map<String, String>.toProperties(): Properties = Properties().also { properties ->
    forEach { (key, value) -> properties.setProperty(key, value) }
  }

  private fun IApiProblem.toReportProblem(
    number: Int,
    currentComponent: IApiComponent,
    baselineComponent: IApiComponent?,
    input: DirectApiAnalyzerInput
  ): ApiAnalysisProblem = ApiAnalysisProblem(
    problemRef = "P%06d".format(number),
    problemId = id,
    messageArguments = messageArguments.toList(),
    problemTypeName = typeName,
    resourcePath = resourcePath,
    severity = severityName(severity),
    line = lineNumber.takeIf { it >= 0 },
    charStart = charStart.takeIf { it >= 0 },
    charEnd = charEnd.takeIf { it >= 0 },
    sourceFile = resourcePath?.substringAfterLast('/'),
    bundleSymbolicName = currentComponent.symbolicName,
    baselineComponentId = baselineComponent?.componentId(),
    currentComponentId = currentComponent.componentId(),
    message = message,
    category = categoryName(category),
    apiFilterFile = input.apiFilterFile?.toAbsolutePath()?.normalize()?.toString()
  )

  private fun IApiComponent.componentId(): String = "$symbolicName:$version"

  private fun severityName(severity: Int): String = when (severity) {
    ApiPlugin.SEVERITY_ERROR -> "error"
    ApiPlugin.SEVERITY_WARNING -> "warning"
    ApiPlugin.SEVERITY_IGNORE -> "ignore"
    else -> severity.toString()
  }

  private fun categoryName(category: Int): String = when (category) {
    IApiProblem.CATEGORY_COMPATIBILITY -> "compatibility"
    IApiProblem.CATEGORY_USAGE -> "usage"
    IApiProblem.CATEGORY_VERSION -> "version"
    IApiProblem.CATEGORY_SINCETAGS -> "since-tags"
    IApiProblem.CATEGORY_API_BASELINE -> "api-baseline"
    IApiProblem.CATEGORY_API_COMPONENT_RESOLUTION -> "component-resolution"
    IApiProblem.CATEGORY_FATAL_PROBLEM -> "fatal"
    IApiProblem.CATEGORY_API_USE_SCAN_PROBLEM -> "api-use-scan"
    else -> category.toString()
  }
}
