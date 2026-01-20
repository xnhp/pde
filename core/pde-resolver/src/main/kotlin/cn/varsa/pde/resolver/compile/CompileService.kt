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
    val ordered = orderWorkspaceSpecs(specs, plan.workspaceDependencies)
    return Output(ordered)
  }

  private fun orderWorkspaceSpecs(
    specs: List<CompileSpec>,
    workspaceDependencies: Map<String, Set<String>>
  ): List<CompileSpec> {
    val workspaceSpecs = specs.filter { it.isWorkspace }
    if (workspaceSpecs.size <= 1) return specs

    val byBsn = workspaceSpecs.associateBy { it.bsn }
    val depsByBsn = workspaceSpecs.associate { spec ->
      val deps = workspaceDependencies[spec.bsn].orEmpty().filter { byBsn.containsKey(it) }.toMutableSet()
      spec.bsn to deps
    }.toMutableMap()
    val inDegree = depsByBsn.mapValues { it.value.size }.toMutableMap()

    val queue = ArrayDeque<String>()
    workspaceSpecs.map { it.bsn }.forEach { bsn ->
      if (inDegree[bsn] == 0) queue.add(bsn)
    }

    val orderedBsns = mutableListOf<String>()
    while (queue.isNotEmpty()) {
      val bsn = queue.removeFirst()
      orderedBsns += bsn
      depsByBsn.forEach { (node, deps) ->
        if (deps.remove(bsn)) {
          val next = (inDegree[node] ?: 0) - 1
          inDegree[node] = next
          if (next == 0) queue.add(node)
        }
      }
    }

    if (orderedBsns.size < workspaceSpecs.size) {
      // Cycle(s) detected; append remaining in original order for stability.
      val remaining = workspaceSpecs.map { it.bsn }.filter { it !in orderedBsns }
      orderedBsns += remaining
    }

    val orderedWorkspace = orderedBsns.mapNotNull { byBsn[it] }
    val nonWorkspace = specs.filterNot { it.isWorkspace }
    return orderedWorkspace + nonWorkspace
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
