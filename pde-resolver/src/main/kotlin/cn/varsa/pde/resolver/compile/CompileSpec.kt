package cn.varsa.pde.resolver.compile

import cn.varsa.pde.resolver.algo.ResolvedBundle
import cn.varsa.pde.resolver.algo.WorkspaceBundleDescriptor
import cn.varsa.pde.resolver.launch.LaunchPlanner

data class CompileSpec(
  val bsn: String,
  val version: String,
  val origin: String,
  val classpath: List<String>,
  val sourceRoots: List<String>,
  val resourceIncludes: List<String>,
  val resourceExcludes: List<String>,
  val compilerPrefs: Map<String, String>,
  val executionEnvironment: String?,
  val outputDirectory: String?,
  val isWorkspace: Boolean
)

object CompileSpecBuilder {
  fun from(plan: LaunchPlanner.PlanResult, workspace: List<WorkspaceBundleDescriptor>): List<CompileSpec> {
    val byPath = workspace.associateBy { it.path.toAbsolutePath().normalize() }
    return plan.selectedBundles.map { rb -> toSpec(rb, byPath) }
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
