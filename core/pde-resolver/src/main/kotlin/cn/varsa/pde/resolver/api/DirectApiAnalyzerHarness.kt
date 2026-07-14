package cn.varsa.pde.resolver.api

import cn.varsa.pde.resolver.manifest.BundleManifest
import org.eclipse.core.resources.IProject
import org.eclipse.core.resources.IResource
import org.eclipse.core.resources.IWorkspaceRoot
import org.eclipse.core.resources.ResourcesPlugin
import org.eclipse.core.runtime.NullProgressMonitor
import org.eclipse.pde.api.tools.internal.builder.BaseApiAnalyzer
import org.eclipse.pde.api.tools.internal.builder.BuildContext
import org.eclipse.pde.api.tools.internal.model.ApiBaseline
import org.eclipse.pde.api.tools.internal.model.BundleComponent
import org.eclipse.pde.api.tools.internal.model.ProjectComponent
import org.eclipse.pde.api.tools.internal.provisional.ApiPlugin
import org.eclipse.pde.api.tools.internal.provisional.model.IApiComponent
import org.eclipse.pde.api.tools.internal.provisional.problems.IApiProblem
import org.eclipse.osgi.service.resolver.BundleDescription
import org.eclipse.osgi.service.resolver.StateObjectFactory
import org.osgi.framework.BundleException
import org.osgi.framework.Constants.SINGLETON_DIRECTIVE
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.time.Clock
import java.time.Instant
import java.util.Dictionary
import java.util.Hashtable
import java.util.Properties
import java.util.jar.JarFile
import java.util.jar.Manifest
import java.util.logging.Level
import java.util.logging.Logger

class DirectApiAnalyzerHarness(
  private val clock: Clock = Clock.systemUTC()
) {
  data class BundleAnalysisOutcome(
    val bundleSymbolicName: String,
    val report: ApiAnalysisReport? = null,
    val failure: Throwable? = null
  ) {
    val succeeded: Boolean get() = failure == null
  }

  data class BatchAnalysisResult(val outcomes: List<BundleAnalysisOutcome>) {
    val allSucceeded: Boolean get() = outcomes.all { it.succeeded }
  }

  fun analyzeBatch(input: BatchApiAnalyzerInput): BatchAnalysisResult {
    require(input.currentBundles.isNotEmpty()) { "Batch analyzer input must contain at least one current bundle." }

    val workspaceRoot = if (input.workspaceDataDir != null) ResourcesPlugin.getWorkspace().root else null

    val currentBaseline = ApiBaseline("current")
    val referenceBaseline = ApiBaseline("baseline")
    try {
      val currentBundlePaths = input.currentBundles
        .map { it.currentBundle.path.toAbsolutePath().normalize().toString() }
        .toSet()
      val sharedDependencyArtifacts = input.dependencyArtifacts.filterNot { artifact ->
        artifact.path.toAbsolutePath().normalize().toString() in currentBundlePaths
      }

      val currentArtifacts = mergeWithSharedDependencyArtifacts(input.currentBundles.map { it.currentBundle }, sharedDependencyArtifacts)
      val referenceArtifacts = mergeWithSharedDependencyArtifacts(input.baselineArtifacts, sharedDependencyArtifacts)
      currentBaseline.addApiComponents(createComponents(currentBaseline, currentArtifacts, workspaceRoot).toTypedArray())
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

  private fun mergeWithSharedDependencyArtifacts(
    scopeOwnArtifacts: List<AnalyzerBundleArtifact>,
    sharedDependencyArtifacts: List<AnalyzerBundleArtifact>
  ): List<AnalyzerBundleArtifact> {
    val ownBsns = scopeOwnArtifacts.map { it.bundleSymbolicName }.toSet()
    val nonConflictingShared = sharedDependencyArtifacts.filterNot { candidate ->
      candidate.bundleSymbolicName in ownBsns
    }
    return scopeOwnArtifacts + nonConflictingShared
  }

  private fun AnalyzerBundleArtifact.isSingletonArtifact(): Boolean {
    val manifest = readManifest(path) ?: return false
    return manifest.bundleSymbolicName?.value?.directive?.get(SINGLETON_DIRECTIVE) == "true"
  }

  private fun readManifest(path: Path): BundleManifest? {
    val file = path.toFile()
    return if (file.isDirectory) {
      File(file, "META-INF/MANIFEST.MF").takeIf { it.isFile }?.inputStream()?.use { BundleManifest.parse(Manifest(it)) }
    } else {
      JarFile(file).use { it.manifest?.let(BundleManifest::parse) }
    }
  }

  private fun createComponents(
    baseline: ApiBaseline,
    artifacts: List<AnalyzerBundleArtifact>,
    workspaceRoot: IWorkspaceRoot? = null
  ): List<IApiComponent> =
    artifacts
      .distinctBy { it.path.toAbsolutePath().normalize().toString() }
      .mapIndexed { index, artifact ->
        val project = workspaceRoot?.let { root ->
          artifact.workspaceProjectName?.let { name -> root.getProject(name) }
        }
        if (project != null && project.exists()) {
          if (!project.isOpen) {
            project.open(IResource.NONE, NullProgressMonitor())
          }
          val componentLocation = projectComponentLocation(artifact)
          createWorkspaceProjectComponent(baseline, componentLocation, artifact.path, project, index)
        } else {
          BundleComponent(baseline, artifact.path.toAbsolutePath().normalize().toString(), index.toLong() + 1).also { component ->
            require(component.isValidBundle) {
              "Analyzer artifact is not a valid OSGi bundle artifact: ${artifact.path}"
            }
          }
        }
      }

  private fun projectComponentLocation(artifact: AnalyzerBundleArtifact): String {
    val projectName = artifact.workspaceProjectName!!
    val sourceDir = artifact.sourcePath
    if (sourceDir != null) {
      val locationsRoot = artifact.path.parent.resolve("project-locations")
      val wrapper = locationsRoot.resolve(projectName)
      if (!Files.exists(wrapper)) {
        Files.createDirectories(wrapper)
        val realManifest = sourceDir.resolve("META-INF")
        if (Files.isDirectory(realManifest)) {
          Files.createSymbolicLink(wrapper.resolve("META-INF"), realManifest)
        }
      }
      return wrapper.toAbsolutePath().normalize().toString()
    }
    return artifact.path.toAbsolutePath().normalize().toString()
  }

  private fun createWorkspaceProjectComponent(
    baseline: ApiBaseline,
    componentLocation: String,
    artifactPath: Path,
    project: IProject,
    index: Int
  ): ProjectComponent {
    val model = org.eclipse.pde.core.plugin.PluginRegistry.findModel(project)
    return object : ProjectComponent(baseline, componentLocation, model, index.toLong() + 1) {
      override fun getBundleDescription(properties: MutableMap<String, String>?, location: String?, id: Long): BundleDescription? {
        @Suppress("UsePropertyAccessSyntax")
        val manifestFile = artifactPath.toFile().let { path ->
          if (path.isDirectory) File(path, "META-INF/MANIFEST.MF")
          else path
        }
        val manifest = if (manifestFile.isFile && !manifestFile.isDirectory) {
          if (manifestFile.name.endsWith(".jar", ignoreCase = true)) {
            JarFile(manifestFile).use { it.manifest }
          } else {
            manifestFile.inputStream().use { Manifest(it) }
          }
        } else {
          throw BundleException("Cannot find META-INF/MANIFEST.MF at $artifactPath")
        }
        val dict: Dictionary<String, String> = Hashtable()
        manifest.mainAttributes.entries.forEach { entry ->
          dict.put(entry.key.toString(), entry.value?.toString() ?: "")
        }
        return try {
          StateObjectFactory.defaultFactory.createBundleDescription(dict, location, id)
        } catch (e: BundleException) {
          throw e
        } catch (e: Exception) {
          throw BundleException("Failed to create bundle description for $artifactPath", e)
        }
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
