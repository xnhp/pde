package cn.varsa.pde.resolver.api

import org.eclipse.core.resources.IProject
import org.eclipse.core.resources.IResource
import org.eclipse.core.resources.IWorkspaceRoot
import org.eclipse.core.resources.ResourcesPlugin
import org.eclipse.core.runtime.NullProgressMonitor
import org.eclipse.core.runtime.jobs.Job
import org.eclipse.jdt.core.JavaCore
import org.eclipse.osgi.service.resolver.BundleSpecification
import org.eclipse.osgi.service.resolver.ImportPackageSpecification
import org.eclipse.pde.api.tools.internal.ApiBaselineManager
import org.eclipse.pde.api.tools.internal.ApiDescriptionManager
import org.eclipse.pde.api.tools.internal.builder.BaseApiAnalyzer
import org.eclipse.pde.api.tools.internal.builder.BuildContext
import org.eclipse.pde.api.tools.internal.model.ApiBaseline
import org.eclipse.pde.api.tools.internal.model.BundleComponent
import org.eclipse.pde.api.tools.internal.model.ProjectComponent
import org.eclipse.pde.api.tools.internal.model.WorkspaceBaseline
import org.eclipse.pde.api.tools.internal.problems.ApiProblemFactory
import org.eclipse.pde.api.tools.internal.provisional.ApiPlugin
import org.eclipse.pde.api.tools.internal.provisional.Factory
import org.eclipse.pde.api.tools.internal.provisional.VisibilityModifiers
import org.eclipse.pde.api.tools.internal.provisional.comparator.IDelta
import org.eclipse.pde.api.tools.internal.provisional.model.IApiComponent
import org.eclipse.pde.api.tools.internal.provisional.model.IApiElement
import org.eclipse.pde.api.tools.internal.provisional.problems.IApiProblem
import org.osgi.framework.Constants
import java.nio.file.Files
import java.nio.file.Path
import java.time.Clock
import java.time.Instant
import java.util.Properties
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
        analyzeOneComponent(bundleInfo, input.preferences, currentBaseline, referenceBaseline, input.applyApiFilters, input.sanityCheck)
      }
      writeFailureSummary(input, outcomes)
      return BatchAnalysisResult(outcomes)
    } finally {
      currentBaseline.dispose()
      referenceBaseline.dispose()
    }
  }

  private fun writeFailureSummary(input: BatchApiAnalyzerInput, outcomes: List<BundleAnalysisOutcome>) {
    val path = input.failureSummaryPath?.let { Path.of(it) } ?: return
    val failures = outcomes.filterNot { it.succeeded }.map { outcome ->
      ApiAnalysisFailure(outcome.bundleSymbolicName, outcome.failure?.message ?: outcome.failure.toString())
    }
    try {
      path.parent?.let { Files.createDirectories(it) }
      Files.writeString(path, ApiAnalysisFailureSummaryJson.write(ApiAnalysisFailureSummary(failures)))
    } catch (e: Exception) {
      logger.log(Level.WARNING, "Failed to write API analysis failure summary to $path", e)
    }
  }

  private fun analyzeOneComponent(
    bundleInfo: CurrentBundleInfo,
    preferences: Map<String, String>,
    currentBaseline: ApiBaseline,
    referenceBaseline: ApiBaseline,
    applyApiFilters: Boolean = true,
    sanityCheck: Boolean = true
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
      // In PDE 1.4+, ProjectComponent.apiDescription wraps ProjectApiDescription
      // in a CompositeApiDescription — extract through fDescriptions.
      if (currentComponent is ProjectComponent) {
        val ad = currentComponent.apiDescription
        try {
          val m = ad.javaClass.getDeclaredMethod("refreshPackages")
          m.isAccessible = true
          m.invoke(ad)
        } catch (_: NoSuchMethodException) {
          val fDescriptions = ad.javaClass.getDeclaredField("fDescriptions").also {
            it.isAccessible = true
          }
          val descs = fDescriptions.get(ad) as Array<*>
          for (desc in descs) {
            try {
              val m = desc!!.javaClass.getDeclaredMethod("refreshPackages")
              m.isAccessible = true
              m.invoke(desc)
            } catch (_: NoSuchMethodException) { /* skip descriptions without refreshPackages */ }
          }
        }
      }

      // Hard failures instead of silent degradation (see ApiAnalysisSanity): an API description
      // that treats exported packages as private, or a dependency that no component in the
      // analyzer input provides, makes every downstream problem list meaningless.
      verifyApiDescriptionCoverage(currentComponent)
      verifyResolverErrors(currentComponent, currentBaseline)

      // PDE emits the "API analysis aborted: unresolved constraints" problem whenever the
      // component's BundleDescription has resolver errors; with continue-on-error it still analyzes
      // through the ApiBaseline (package-based resolution), so the warning alone is not fatal.
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

      val existingFilterRules = bundleInfo.apiFilterPath?.let { ApiFilterRule.load(it) } ?: emptyList()
      val filterRules = if (applyApiFilters) existingFilterRules else emptyList()

      val allProblems = analyzer.getProblems().toList()
      if (sanityCheck) {
        ApiAnalysisSanity.degradedResultMessage(
          noLongerApiTypeCount = allProblems.count { it.isNoLongerApiType() },
          unusedFilterCount = allProblems.count { it.isUnusedFilter() },
          totalFilterCount = existingFilterRules.size
        )?.let { throw DegradedApiAnalysisException("$bsn: $it") }
      }
      val filteredProblems = if (filterRules.isNotEmpty()) {
        allProblems.filter { problem ->
          filterRules.none { rule ->
            rule.matches(
              problemId = problem.id,
              typeName = problem.typeName,
              resourcePath = problem.resourcePath,
              messageArguments = problem.messageArguments.toList()
            )
          }
        }
      } else allProblems

      val suppressed = allProblems.size - filteredProblems.size
      if (suppressed > 0) {
        logger.log(Level.INFO, "API filters suppressed $suppressed problem(s) for bundle $bsn")
      }

      val baselineComponent = referenceBaseline.getApiComponent(bsn)
      val report = ApiAnalysisReport(
        generatedAt = Instant.now(clock).toString(),
        tool = "pde api-baseline check direct",
        problems = filteredProblems.mapIndexed { index, problem ->
          problem.toReportProblem(index + 1, currentComponent, baselineComponent, bundleInfo.apiFilterPath)
        }
      )
      bundleInfo.outputReportPath.parent?.let { Files.createDirectories(it) }
      Files.writeString(bundleInfo.outputReportPath, ApiAnalysisReportJson.write(report))
      BundleAnalysisOutcome(bsn, report = report)
    } catch (failure: Throwable) {
      logger.log(Level.SEVERE, "API analysis failed for bundle $bsn", failure)
      // A report from an earlier run at the same path would otherwise be mistaken for this run's
      // result by add-filter / add-all-from-report.
      if (Files.deleteIfExists(bundleInfo.outputReportPath)) {
        logger.warning("Deleted stale API baseline report ${bundleInfo.outputReportPath} because this run failed.")
      }
      BundleAnalysisOutcome(bsn, failure = failure)
    } finally {
      analyzer.dispose()
    }
  }

  /**
   * Every exported, non-internal package the current component has types for must resolve as API
   * in its API description. When the description is missing or incomplete (see
   * prepareWorkspaceProject), PDE reports every type in the affected packages as "no longer an API"
   * and every filter as unused instead of failing.
   */
  private fun verifyApiDescriptionCoverage(component: IApiComponent) {
    if (component !is ProjectComponent) return
    val description = component.apiDescription
    val exportedApiPackages = component.bundleDescription.exportPackages
      .filterNot { export ->
        export.getDirective("x-internal") == true || (export.getDirective("x-friends") as? Array<*>)?.isNotEmpty() == true
      }
      .map { it.name }
      .toSet()
    val packagesWithTypes = component.apiTypeContainers
      .filter { container ->
        (container.getAncestor(IApiElement.COMPONENT) as? IApiComponent)?.symbolicName == component.symbolicName
      }
      .flatMap { it.packageNames.asList() }
      .toSet()
    if (exportedApiPackages.isNotEmpty() && packagesWithTypes.isEmpty()) {
      throw DegradedApiAnalysisException(
        "${component.symbolicName}: the workspace project exposes no types to API Tools (no API type containers; " +
          "check build.properties source./output. entries and that the output folder is compiled). " +
          "Since-tag and compatibility analysis would be wrong; not writing a report."
      )
    }
    val packagesResolvedAsApi = exportedApiPackages.filter { name ->
      val annotations = description.resolveAnnotations(Factory.packageDescriptor(name))
      annotations != null && VisibilityModifiers.isAPI(annotations.visibility)
    }.toSet()
    val missing = ApiAnalysisSanity.missingApiPackages(exportedApiPackages, packagesWithTypes, packagesResolvedAsApi)
    if (missing.isNotEmpty()) {
      throw DegradedApiAnalysisException(
        "${component.symbolicName}: API description is incomplete; ${missing.size} exported package(s) with types resolve as " +
          "non-API: ${missing.sorted().joinToString(", ")}. PDE would report every type in them as 'no longer an API'; " +
          "not writing a report."
      )
    }
  }

  /**
   * Resolver errors on the current component are tolerated (continue-on-error) as long as every
   * unsatisfied mandatory constraint is provided by some component in the analyzer input; API Tools
   * resolves references through the baseline by package, not through the OSGi state. A dependency
   * that is absent entirely means references into it cannot be resolved at all.
   */
  private fun verifyResolverErrors(component: IApiComponent, baseline: ApiBaseline) {
    val errors = component.errors ?: return
    val missing = errors.mapNotNull { error ->
      val constraint = error.unsatisfiedConstraint ?: return@mapNotNull null
      val provided = when (constraint) {
        is ImportPackageSpecification -> {
          if (constraint.getDirective(Constants.RESOLUTION_DIRECTIVE) == ImportPackageSpecification.RESOLUTION_OPTIONAL) return@mapNotNull null
          baseline.resolvePackage(component, constraint.name).isNotEmpty()
        }
        is BundleSpecification -> {
          if (constraint.isOptional) return@mapNotNull null
          baseline.getApiComponent(constraint.name) != null
        }
        else -> baseline.getApiComponent(constraint.name) != null
      }
      if (provided) null else "${constraint.name} ${constraint.versionRange ?: ""}".trim()
    }
    // Warning, not a hard failure: compile-time-only bundles such as org.eclipse.jdt.annotation are
    // routinely absent from the analyzer input while the analysis itself is complete (P000001 is
    // reported for them either way). The coverage check and the degraded-result heuristic catch the
    // case where the missing dependency actually breaks the API description.
    if (missing.isNotEmpty()) {
      logger.warning(
        "${component.symbolicName}: component resolution aborted and ${missing.size} required dependenc(y/ies) are absent " +
          "from the analyzer input: ${missing.joinToString(", ")}. References into them cannot be analyzed."
      )
    }
  }

  private fun IApiProblem.isNoLongerApiType(): Boolean =
    category == IApiProblem.CATEGORY_COMPATIBILITY &&
      ApiProblemFactory.getProblemKind(id) == IDelta.REMOVED &&
      ApiProblemFactory.getProblemFlags(id) == IDelta.API_TYPE

  private fun IApiProblem.isUnusedFilter(): Boolean =
    category == IApiProblem.CATEGORY_USAGE &&
      ApiProblemFactory.getProblemKind(id) == IApiProblem.UNUSED_PROBLEM_FILTERS

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
          } catch (e: Exception) {
            logger.log(Level.WARNING, "JavaCore.initializeAfterLoad failed for project ${project.name}", e)
          }
          prepareWorkspaceProject(project)
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

  /**
   * Make the JDT view of the project deterministic before API Tools looks at it.
   *
   * The workspace `-data` dir is reused across CLI runs, and the analyzer saves the workspace on
   * exit, which lets ApiDescriptionManager persist the project's API description
   * (.metadata/.plugins/org.eclipse.pde.api.tools/<project>/.api_description). On the next run
   * that description is RESTORED instead of rebuilt: each package node is pinned to the exact
   * IPackageFragments it was created with -- including a fragment in the bundle's own compiled
   * output folder, which JDT models as an external class folder (linked into
   * .org.eclipse.jdt.core.external.folders and populated by a background refresh job).
   * ProjectApiDescription.PackageNode.refresh() drops the whole package as soon as ANY of its
   * fragments does not exist yet, and never rebuilds it, so every type in that package becomes
   * "no longer an API" and every since-tag filter unused. Which packages lose the race against the
   * refresh job depends on machine load, which is the run-to-run variance seen in the reports.
   * A description built from scratch (first run against a fresh workspace) only records fragments
   * that exist at that moment and is therefore robust; make every run behave like the first one.
   */
  private fun prepareWorkspaceProject(project: IProject) {
    val javaProject = JavaCore.create(project)
    try {
      ApiDescriptionManager.getManager().clean(javaProject, true, true)
    } catch (e: Exception) {
      logger.log(Level.WARNING, "Failed to discard persisted API description for project ${project.name}", e)
    }
    // Bring the resource tree in line with disk synchronously (the setup process may have been a
    // different JVM), then force classpath resolution so JDT creates its external class-folder
    // links, and wait for the refresh jobs those trigger instead of racing them.
    project.refreshLocal(IResource.DEPTH_INFINITE, NullProgressMonitor())
    try {
      javaProject.getResolvedClasspath(true)
      javaProject.packageFragmentRoots
    } catch (e: Exception) {
      logger.log(Level.WARNING, "Classpath resolution failed for project ${project.name}", e)
    }
    listOf(ResourcesPlugin.FAMILY_AUTO_REFRESH, ResourcesPlugin.FAMILY_MANUAL_REFRESH).forEach { family ->
      try {
        Job.getJobManager().join(family, NullProgressMonitor())
      } catch (e: Exception) {
        logger.log(Level.WARNING, "Waiting for workspace refresh jobs failed for project ${project.name}", e)
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
    val component = ProjectComponent(baseline, location, model, index.toLong() + 1)
    // Correcting fProject is load-bearing for since-tag analysis: if it stays pointed at the wrong
    // (location-derived) project name, the analysis silently produces no since-tag deltas. Fail loudly
    // rather than swallow, so a future PDE API Tools version renaming the field can't reintroduce the
    // exact silent-fallback class of bug this whole change set fixed.
    try {
      val f = ProjectComponent::class.java.getDeclaredField("fProject")
      f.isAccessible = true
      f.set(component, JavaCore.create(project))
    } catch (e: Exception) {
      throw IllegalStateException(
        "Failed to bind ProjectComponent to workspace project ${project.name} via fProject reflection; " +
          "since-tag analysis would silently produce wrong results.",
        e
      )
    }
    return component
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
    message = renderProblemMessage(category, messageArguments.toList(), message),
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

    // Mirrored so tests without the PDE jar on their classpath can exercise the rendering.
    internal const val CATEGORY_COMPATIBILITY: Int = IApiProblem.CATEGORY_COMPATIBILITY
    internal const val CATEGORY_API_COMPONENT_RESOLUTION: Int = IApiProblem.CATEGORY_API_COMPONENT_RESOLUTION

    /**
     * PDE's own text for an unresolved-constraints problem is "API analysis aborted: {0} has
     * unresolved constraints: {1}", which reads as if the whole run stopped. That text is written
     * for the IDE builder, which really does return early. This harness calls
     * `setContinueOnResolverError(true)`, so BaseApiAnalyzer#analyzeComponent records the problem
     * and then runs the compatibility, version, usage, since-tag and unused-filter checks as
     * usual. Reword so the reader knows the report is complete, and what is unreliable in it.
     */
    internal fun renderProblemMessage(category: Int, messageArguments: List<String>, original: String): String {
      if (category != IApiProblem.CATEGORY_API_COMPONENT_RESOLUTION) return original
      val bsn = messageArguments.getOrNull(0) ?: return original
      val constraints = messageArguments.getOrNull(1) ?: return original
      return "Component resolution incomplete for $bsn: unresolved constraints: $constraints. " +
        "Compatibility, version, @since and usage analysis continued for the whole bundle; " +
        "findings that involve types from the unresolved bundles may be missing or incomplete."
    }
  }
}
