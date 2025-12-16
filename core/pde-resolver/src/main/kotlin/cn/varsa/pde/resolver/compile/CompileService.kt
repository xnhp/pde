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
    val specs = plan.selectedBundles.map { rb -> toSpec(rb, byPath) }
    return Output(specs)
  }

  private fun toSpec(rb: ResolvedBundle, byPath: Map<java.nio.file.Path, WorkspaceBundleDescriptor>): CompileSpec {
    val w = if (rb.isWorkspace) byPath[rb.path.toAbsolutePath().normalize()] else null
    return CompileSpec(
      bsn = rb.bsn,
      version = rb.version.toString(),
      origin = if (rb.isWorkspace) "workspace" else "target",
      classpath = rb.classPathEntries.map { it.toString() },
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
