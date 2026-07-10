package cn.varsa.pde.resolver.api

import org.eclipse.core.runtime.NullProgressMonitor
import org.eclipse.pde.api.tools.internal.builder.BaseApiAnalyzer
import org.eclipse.pde.api.tools.internal.builder.BuildContext
import org.eclipse.pde.api.tools.internal.model.ApiBaseline
import org.eclipse.pde.api.tools.internal.model.BundleComponent
import org.eclipse.pde.api.tools.internal.provisional.ApiPlugin
import org.eclipse.pde.api.tools.internal.provisional.model.IApiComponent
import org.eclipse.pde.api.tools.internal.provisional.problems.IApiProblem
import java.nio.file.Files
import java.nio.file.Path
import java.time.Clock
import java.time.Instant
import java.util.Properties
import java.util.logging.Level
import java.util.logging.Logger

/**
 * Runs Eclipse PDE API Tools analysis inside the single launched Equinox JVM.
 *
 * A batch may contain several "current" bundles that all get analyzed against the same
 * reference (baseline) and dependency artifacts. Building [ApiBaseline] instances is the
 * expensive part of this process (OSGi bundle resolution), so both baselines are built
 * exactly ONCE per batch and shared across every current component. [BaseApiAnalyzer] holds
 * per-run problem/listener state, so a fresh instance is created per current component to
 * keep problems reported for one bundle from leaking into another bundle's report.
 */
class DirectApiAnalyzerHarness(
  private val clock: Clock = Clock.systemUTC()
) {
  /** Outcome of analyzing a single current bundle within a batch. */
  data class BundleAnalysisOutcome(
    val bundleSymbolicName: String,
    val report: ApiAnalysisReport? = null,
    val failure: Throwable? = null
  ) {
    val succeeded: Boolean get() = failure == null
  }

  /**
   * Result of a batch run. Failure semantics: a bundle whose analysis throws does not abort the
   * batch — every other bundle is still analyzed and (if successful) gets its report written.
   * [allSucceeded] tells the caller whether the overall process should exit non-zero.
   */
  data class BatchAnalysisResult(val outcomes: List<BundleAnalysisOutcome>) {
    val allSucceeded: Boolean get() = outcomes.all { it.succeeded }
  }

  /**
   * Analyzes every [BatchApiAnalyzerInput.currentBundles] entry inside ONE shared pair of
   * baselines built for the whole batch. A current bundle that fails to resolve/analyze is
   * recorded as a failed [BundleAnalysisOutcome] and does not prevent the remaining bundles in
   * the batch from being analyzed.
   */
  fun analyzeBatch(input: BatchApiAnalyzerInput): BatchAnalysisResult {
    require(input.currentBundles.isNotEmpty()) { "Batch analyzer input must contain at least one current bundle." }

    val currentBaseline = ApiBaseline("current")
    val referenceBaseline = ApiBaseline("baseline")
    try {
      val currentBundlePaths = input.currentBundles
        .map { it.currentBundle.path.toAbsolutePath().normalize().toString() }
        .toSet()
      // Dependency artifacts materialized for one selected bundle can coincidentally be another
      // selected bundle's own artifact (e.g. two workspace bundles depend on each other); the
      // current-bundle entry always wins so it isn't added to the shared baseline twice.
      val sharedDependencyArtifacts = input.dependencyArtifacts.filterNot { artifact ->
        artifact.path.toAbsolutePath().normalize().toString() in currentBundlePaths
      }

      val currentArtifacts = input.currentBundles.map { it.currentBundle } + sharedDependencyArtifacts
      val referenceArtifacts = input.baselineArtifacts + sharedDependencyArtifacts
      currentBaseline.addApiComponents(createComponents(currentBaseline, currentArtifacts).toTypedArray())
      referenceBaseline.addApiComponents(createComponents(referenceBaseline, referenceArtifacts).toTypedArray())

      val outcomes = input.currentBundles.map { bundleInfo ->
        analyzeOneComponent(bundleInfo, input.preferences, currentBaseline, referenceBaseline)
      }
      return BatchAnalysisResult(outcomes)
    } finally {
      currentBaseline.dispose()
      referenceBaseline.dispose()
    }
  }

  private fun analyzeOneComponent(
    bundleInfo: CurrentBundleInfo,
    preferences: Map<String, String>,
    currentBaseline: ApiBaseline,
    referenceBaseline: ApiBaseline
  ): BundleAnalysisOutcome {
    val bsn = bundleInfo.currentBundle.bundleSymbolicName
    // Fresh analyzer per component: BaseApiAnalyzer accumulates problems/listener state across
    // calls, so reusing one instance across bundles would let bundle A's problems leak into
    // bundle B's report.
    val analyzer = BaseApiAnalyzer()
    return try {
      val currentComponent = currentBaseline.getApiComponent(bsn)
        ?: error("Current bundle not found in analyzer baseline: $bsn")

      analyzer.setContinueOnResolverError(true)
      analyzer.analyzeComponent(
        null,
        null,
        preferences.toProperties(),
        referenceBaseline,
        currentComponent,
        BuildContext(),
        NullProgressMonitor()
      )

      val baselineComponent = referenceBaseline.getApiComponent(bsn)
      val report = ApiAnalysisReport(
        generatedAt = Instant.now(clock).toString(),
        tool = "pde api-analyze direct",
        problems = analyzer.getProblems().mapIndexed { index, problem ->
          problem.toReportProblem(index + 1, currentComponent, baselineComponent, bundleInfo.apiFilterPath)
        }
      )
      bundleInfo.outputReportPath.parent?.let { Files.createDirectories(it) }
      Files.writeString(bundleInfo.outputReportPath, ApiAnalysisReportJson.write(report))
      BundleAnalysisOutcome(bsn, report = report)
    } catch (failure: Throwable) {
      logger.log(Level.SEVERE, "API analysis failed for bundle $bsn", failure)
      BundleAnalysisOutcome(bsn, failure = failure)
    } finally {
      analyzer.dispose()
    }
  }

  private fun createComponents(baseline: ApiBaseline, artifacts: List<AnalyzerBundleArtifact>): List<BundleComponent> =
    artifacts
      .distinctBy { it.path.toAbsolutePath().normalize().toString() }
      .mapIndexed { index, artifact ->
        BundleComponent(baseline, artifact.path.toAbsolutePath().normalize().toString(), index.toLong() + 1).also { component ->
          require(component.isValidBundle) {
            "Analyzer artifact is not a valid OSGi bundle artifact: ${artifact.path}"
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
    apiFilterPath: Path?
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
    apiFilterFile = apiFilterPath?.toAbsolutePath()?.normalize()?.toString()
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

  companion object {
    private val logger = Logger.getLogger(DirectApiAnalyzerHarness::class.java.name)
  }
}
