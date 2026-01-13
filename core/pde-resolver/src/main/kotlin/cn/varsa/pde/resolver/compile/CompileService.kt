package cn.varsa.pde.resolver.compile

import cn.varsa.pde.resolver.algo.ResolvedBundle
import cn.varsa.pde.resolver.algo.WorkspaceBundleDescriptor
import cn.varsa.pde.resolver.launch.LaunchPlanner

/**
 * Transform LaunchPlanner output + workspace descriptors into CompileSpecs.
 * This layer is pure data prep; actual compilation (ECJ/javac, resource copy) is handled elsewhere.
 */
object CompileService {

  data class Output(val specs: List<CompileSpec>)

  fun buildSpecs(plan: LaunchPlanner.PlanResult, workspace: List<WorkspaceBundleDescriptor>): Output {
    val byPath = workspace.associateBy { it.path.toAbsolutePath().normalize() }
    val allClassPathEntries: List<String> = plan.selectedBundles
      .flatMap { it.classPathEntries }
      .map { it.toAbsolutePath().normalize().toString() }
    val specs = plan.selectedBundles.map { rb -> toSpec(rb, byPath, allClassPathEntries) }
    return Output(specs)
  }

  private fun toSpec(
    rb: ResolvedBundle,
    byPath: Map<java.nio.file.Path, WorkspaceBundleDescriptor>,
    allClassPathEntries: List<String>
  ): CompileSpec {
    val w = if (rb.isWorkspace) byPath[rb.path.toAbsolutePath().normalize()] else null
    val effectiveClasspath = linkedSetOf<String>().apply {
      // Prefer bundle-specific classPathEntries first (bin includes, Bundle-ClassPath)
      addAll(rb.classPathEntries.map { it.toAbsolutePath().normalize().toString() })
      // Then add all resolved bundle locations (workspace + TP) for dependencies
      addAll(allClassPathEntries)
    }.toList()
    return CompileSpec(
      bsn = rb.bsn,
      version = rb.version.toString(),
      origin = if (rb.isWorkspace) "workspace" else "target",
      bundlePath = rb.path.toString(),
      classpath = effectiveClasspath,
      sourceRoots = w?.sourceRoots?.map { it.toString() } ?: emptyList(),
      resourceIncludes = w?.resourceIncludes ?: emptyList(),
      resourceExcludes = w?.resourceExcludes ?: emptyList(),
      compilerPrefs = w?.compilerPrefs ?: emptyMap(),
      executionEnvironment = w?.executionEnvironment,
      outputDirectory = w?.outputDirectory?.toString(),
      isWorkspace = rb.isWorkspace
    )
  }
}
