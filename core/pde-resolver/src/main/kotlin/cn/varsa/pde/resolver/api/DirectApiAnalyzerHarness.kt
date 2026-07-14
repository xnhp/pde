package cn.varsa.pde.resolver.api

import cn.varsa.pde.resolver.manifest.BundleManifest
import org.eclipse.core.resources.IProject
import org.eclipse.core.resources.IResource
import org.eclipse.core.resources.IWorkspaceRoot
import org.eclipse.core.resources.ResourcesPlugin
import org.eclipse.core.runtime.NullProgressMonitor
import org.eclipse.jdt.core.JavaCore
import org.eclipse.pde.api.tools.internal.ApiDescriptionManager
import org.eclipse.pde.api.tools.internal.ApiBaselineManager
import org.eclipse.pde.api.tools.internal.builder.BaseApiAnalyzer
import org.eclipse.pde.api.tools.internal.builder.BuildContext
import org.eclipse.pde.api.tools.internal.model.ApiBaseline
import org.eclipse.pde.api.tools.internal.model.BundleComponent
import org.eclipse.pde.api.tools.internal.model.ProjectComponent
import org.eclipse.pde.api.tools.internal.model.WorkspaceBaseline
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
import java.util.jar.JarEntry
import java.util.jar.JarFile
import java.util.jar.JarOutputStream
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

    val currentBundlePaths = input.currentBundles
      .map { it.currentBundle.path.toAbsolutePath().normalize().toString() }
      .toSet()
    val sharedDependencyArtifacts = input.dependencyArtifacts.filterNot { artifact ->
      artifact.path.toAbsolutePath().normalize().toString() in currentBundlePaths
    }

    val currentArtifacts = mergeWithSharedDependencyArtifacts(input.currentBundles.map { it.currentBundle }, sharedDependencyArtifacts)
    val referenceArtifacts = mergeWithSharedDependencyArtifacts(input.baselineArtifacts, sharedDependencyArtifacts)

    // The current/project side must reuse the shared PDE model state so that ProjectComponents,
    // whose BundleDescriptions belong to that state, can be registered without the
    // "bundle belongs to another state" conflict a fresh ApiBaseline state causes. WorkspaceBaseline
    // is exactly the IDE's baseline for this: getState() returns PDECore's model-manager state and
    // addApiComponents() registers components without re-adding/re-resolving their descriptions.
    val currentBaseline: ApiBaseline = if (workspaceRoot != null) {
      val wb = WorkspaceBaseline()
      // Must register with ApiBaselineManager AFTER createComponents(),
      // because createComponents() → PluginRegistry.findModel() triggers PDE
      // workspace scanning which calls disposeWorkspaceBaseline() — if we set
      // workspacebaseline before, our baseline gets disposed and addComponent
      // silently does nothing (isDisposed() check).
      val components = createComponents(wb, currentArtifacts, workspaceRoot)
      val mgr = ApiBaselineManager.getManager()
      mgr.javaClass.getDeclaredField("workspacebaseline").let { f ->
        f.isAccessible = true
        f.set(mgr, wb)
      }
      // WorkspaceBaseline.addApiComponents() skips ProjectComponents (source)
      // and only registers BundleComponents (binary). We must use the protected
      // addComponent() directly for our ProjectComponents.
      val addComp = ApiBaseline::class.java.getDeclaredMethod(
        "addComponent", IApiComponent::class.java
      ).also { it.isAccessible = true }
      components.forEach { c -> addComp.invoke(wb, c) }
      wb
    } else {
      val cb = ApiBaseline("current")
      cb.addApiComponents(createComponents(cb, currentArtifacts).toTypedArray())
      cb
    }
    val referenceBaseline = ApiBaseline("baseline")
    referenceBaseline.addApiComponents(createComponents(referenceBaseline, referenceArtifacts).toTypedArray())
    try {
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

      // In PDE API Tools 1.3.600, ProjectApiDescription's constructor does not
      // call refreshPackages(); without it fPackageMap stays empty and all
      // workspace types are reported as REMOVED instead of ADDED, so since-tag
      // checks never fire.
      if (currentComponent is ProjectComponent) {
        val ad = currentComponent.apiDescription
        val m = ad.javaClass.getDeclaredMethod("refreshPackages")
        m.isAccessible = true
        m.invoke(ad)
      }

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
          try {
            JavaCore.initializeAfterLoad(NullProgressMonitor())
          } catch (_: Exception) {}
          // ApiDescriptionManager only builds a real (export-aware) ProjectApiDescription when the
          // project carries the API Tools nature; without it the description is a
          // NonApiProjectDescription that treats every package as non-API, so the analyzed type is
          // reported as "no longer an API" instead of yielding a since-tag delta. The API nature
          // requires-nature the PDE Plugin nature, so add both. AVOID_NATURE_CONFIG keeps the natures'
          // builders (PDE/API analysis builders) from being wired in and run -- we only need
          // hasNature() to be true so Util.isApiProject() passes. Done here (analyzer runtime, which
          // has org.eclipse.pde.api.tools) rather than at setup time, where that bundle may be absent.
          val requiredNatures = listOf("org.eclipse.pde.PluginNature", ApiPlugin.NATURE_ID)
          val missingNatures = requiredNatures.filterNot { project.hasNature(it) }
          if (missingNatures.isNotEmpty()) {
            val description = project.description
            description.natureIds = description.natureIds + missingNatures
            project.setDescription(description, IResource.AVOID_NATURE_CONFIG, NullProgressMonitor())
          }
          createWorkspaceProjectComponent(baseline, artifact, project, index)
        } else {
          BundleComponent(baseline, artifact.path.toAbsolutePath().normalize().toString(), index.toLong() + 1).also { component ->
            require(component.isValidBundle) {
              "Analyzer artifact is not a valid OSGi bundle artifact: ${artifact.path}"
            }
          }
        }
      }

  private fun createWorkspaceProjectComponent(
    baseline: ApiBaseline,
    artifact: AnalyzerBundleArtifact,
    project: IProject,
    index: Int
  ): ProjectComponent {
    val model = org.eclipse.pde.core.plugin.PluginRegistry.findModel(project)
    // BundleComponent.init() loads the manifest straight off the filesystem `location`, so location
    // must physically contain META-INF/MANIFEST.MF -- that's the bundle artifact directory, not the
    // invisible project's own (link-only) location dir. ProjectComponent's constructor ALSO derives
    // its IJavaProject from location.lastSegment() treated as a project name, which won't match our
    // hashed project name, so we correct fProject by reflection to the real workspace project.
    // Combined with the build.properties WorkspaceSetupService writes, ProjectComponent then discovers
    // API types from the JDT output folder, and the resolved PDE model supplies Export-Package info --
    // so the wrapper jar and getBundleDescription override the previous approach relied on are gone.
    val location = artifact.path.toAbsolutePath().normalize().toString()
    return object : ProjectComponent(baseline, location, model, index.toLong() + 1) {
      init {
        try {
          val f = ProjectComponent::class.java.getDeclaredField("fProject")
          f.isAccessible = true
          f.set(this, JavaCore.create(project))
        } catch (_: Exception) {}
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
