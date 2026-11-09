package cn.varsa.pde.resolver.compile

import cn.varsa.pde.resolver.algo.ResolveOptions
import cn.varsa.pde.resolver.algo.Resolver
import cn.varsa.pde.resolver.index.TargetPlatformIndex
import cn.varsa.pde.resolver.manifest.fragmentHostAndVersionRange
import org.osgi.framework.VersionRange
import cn.varsa.pde.resolver.algo.ResolvedBundle
import cn.varsa.pde.resolver.algo.WorkspaceBundleDescriptor
import cn.varsa.pde.resolver.launch.LaunchPlanner

/**
 * Transform LaunchPlanner output + workspace descriptors into CompileSpecs.
 * This layer is pure data prep; actual compilation (ECJ/javac, resource copy) is handled elsewhere.
 */
object CompileService {

  data class Output(val specs: List<CompileSpec>)

  /** Build isolated compile closures, including fragments attached to the selected host versions. */
  fun buildSpecs(
    plan: LaunchPlanner.PlanResult,
    workspace: List<WorkspaceBundleDescriptor>,
    target: TargetPlatformIndex,
    options: ResolveOptions
  ): Output {
    val workspaceByPath = workspace.associateBy { it.path.toAbsolutePath().normalize() }
    val fragments = plan.selectedBundles.mapNotNull { bundle ->
      if (bundle.fragmentHost == null && !bundle.isWorkspace) return@mapNotNull null
      val descriptor = if (bundle.isWorkspace) {
        workspaceByPath[bundle.path.toAbsolutePath().normalize()]
      } else {
        val exact = VersionRange(VersionRange.LEFT_CLOSED, bundle.version, bundle.version, VersionRange.RIGHT_CLOSED)
        target.get(bundle.bsn, exact)?.let {
          WorkspaceBundleDescriptor(bundle.path, it.manifest, bundle.classPathEntries)
        }
      } ?: return@mapNotNull null
      val host = descriptor.fragmentHost?.let { it.symbolicName to it.versionRange }
        ?: descriptor.manifest.fragmentHostAndVersionRange() ?: return@mapNotNull null
      Triple(bundle, descriptor, host)
    }
    val fragmentClosures = mutableMapOf<ResolvedBundle, List<ResolvedBundle>>()
    val dependencies = plan.workspaceDependencies.toMutableMap()
    val classpaths = workspace.associate { entry ->
      val bsn = requireNotNull(entry.manifest.bundleSymbolicName?.key)
      val bundles = Resolver.resolve(target, workspace, entry, options).bundles.toMutableList()
      val attached = mutableSetOf<ResolvedBundle>()
      // A fragment's dependencies can introduce another host. Keep attaching until stable.
      do {
        var changed = false
        fragments.forEach { (fragment, descriptor, host) ->
          if (fragment in attached || bundles.none {
              it.bsn == host.first && (host.second?.includes(it.version) != false)
            }) return@forEach
          attached += fragment
          // Preserve each entry's dependency versions when a fragment's closure overlaps them.
          val closure = fragmentClosures.getOrPut(fragment) {
            Resolver.resolve(target, workspace, descriptor, options).bundles.map {
              if (it.bsn == fragment.bsn) fragment else it
            }
          }
          closure.forEach { candidate ->
            if (bundles.none { it.bsn == candidate.bsn }) bundles += candidate
          }
          changed = true
        }
      } while (changed)
      // Fragments already compile after their workspace host. Do not add the reverse edge;
      // consumers depend on both, while host/fragment source cycles still need a joint compiler.
      dependencies[bsn] = dependencies[bsn].orEmpty() + bundles
        .filter { candidate ->
          candidate.isWorkspace && candidate.bsn != bsn && fragments.none { (fragment, _, host) ->
            fragment.bsn == candidate.bsn && host.first == bsn
          }
        }.map { it.bsn }
      bsn to bundles.flatMap { it.classPathEntries }
        .map { it.toAbsolutePath().normalize().toString() }.distinct()
    }
    return buildSpecs(plan.copy(workspaceDependencies = dependencies), workspace, classpaths)
  }

  /**
   * @param perEntryClasspath optional per-workspace-bundle compile classpath (BSN -> entries),
   *   each derived from that bundle's OWN resolve closure. When present for a workspace bundle, it
   *   is used instead of the aggregate union of all bundles' closures, so a bundle compiles against
   *   the library versions its own ranges select (matching its launch wiring). Falls back to the
   *   union when absent.
   */
  fun buildSpecs(
    plan: LaunchPlanner.PlanResult,
    workspace: List<WorkspaceBundleDescriptor>,
    perEntryClasspath: Map<String, List<String>> = emptyMap()
  ): Output {
    val byPath = workspace.associateBy { it.path.toAbsolutePath().normalize() }
    val allClassPathEntries: List<String> = plan.selectedBundles
      .flatMap { it.classPathEntries }
      .map { it.toAbsolutePath().normalize().toString() }
    val specs = plan.selectedBundles.map { rb -> toSpec(rb, byPath, allClassPathEntries, perEntryClasspath) }
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
    allClassPathEntries: List<String>,
    perEntryClasspath: Map<String, List<String>>
  ): CompileSpec {
    val w = if (rb.isWorkspace) byPath[rb.path.toAbsolutePath().normalize()] else null
    val ownClasspath = rb.classPathEntries.map { it.toAbsolutePath().normalize().toString() }
    val effectiveClasspath = linkedSetOf<String>().apply {
      // Prefer bundle-specific classPathEntries first (bin includes, Bundle-ClassPath)
      addAll(ownClasspath)
      // A workspace bundle compiles against ITS OWN resolved closure (honoring its declared
      // Require-Bundle ranges) when one is supplied, not the global union of every bundle's closure.
      // The union can carry several versions of a multi-version library, which makes ecj bind an
      // overload the bundle won't have at runtime. Falls back to the union when no per-entry
      // classpath is provided.
      val own = if (rb.isWorkspace) perEntryClasspath[rb.bsn] else null
      addAll(own ?: allClassPathEntries)
    }.toList()
    return CompileSpec(
      bsn = rb.bsn,
      version = rb.version.toString(),
      origin = if (rb.isWorkspace) "workspace" else "target",
      bundlePath = rb.path.toString(),
      classpath = effectiveClasspath,
      ownClasspath = ownClasspath,
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
