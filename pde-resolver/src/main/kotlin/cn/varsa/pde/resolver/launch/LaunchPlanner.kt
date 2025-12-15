package cn.varsa.pde.resolver.launch

import cn.varsa.pde.resolver.algo.BundleSelectionAccumulator
import cn.varsa.pde.resolver.algo.ResolveProblem
import cn.varsa.pde.resolver.algo.ResolveProblemType
import cn.varsa.pde.resolver.algo.ResolveResult
import cn.varsa.pde.resolver.algo.Resolver
import cn.varsa.pde.resolver.algo.ResolvedBundle

object LaunchPlanner {
  data class PlanResult(
    val plan: LauncherPlan,
    val context: LaunchContext,
    val problemsByScope: Map<String, List<ResolveProblem>> = emptyMap(),
    val selectedBundles: List<ResolvedBundle> = emptyList()
  )

  fun build(
    environment: LaunchEnvironment,
    options: LauncherOptions,
    session: LaunchResolveSession? = null
  ): PlanResult {
    val startupLevels = environment.startupLevels.toMutableMap()
    val selector = BundleSelectionAccumulator(preferWorkspace = true)
    val problems = linkedMapOf<String, MutableList<ResolveProblem>>()

    fun levelFor(bsn: String): Int =
      startupLevels[bsn]
        ?: environment.startLevelProvider?.invoke(bsn)
        ?: options.defaultStartLevel

    fun autoStartFor(bsn: String, level: Int): Boolean =
      environment.autoStartBundles[bsn]
        ?: environment.autoStartProvider?.invoke(bsn)
        ?: (options.autoStartDefault && level >= 0)

    fun recordProblems(scope: String, items: List<ResolveProblem>) {
      if (items.isEmpty()) return
      problems.getOrPut(scope) { mutableListOf() }.addAll(items)
    }

    fun scopeName(entry: cn.varsa.pde.resolver.algo.WorkspaceBundleDescriptor): String =
      entry.manifest.bundleSymbolicName?.key ?: entry.path.fileName?.toString() ?: entry.path.toString()

    fun ResolveResult.toProblemList(): List<ResolveProblem> =
      if (this.problems.isNotEmpty()) this.problems
      else unresolved.map { unresolved ->
        ResolveProblem(
          type = ResolveProblemType.MISSING_BUNDLE,
          symbol = unresolved.bsn,
          range = unresolved.range,
          message = unresolved.reason
        )
      }

    environment.workspaceEntries.forEach { entry ->
      val cached = session?.get(entry)
      val result = cached ?: Resolver.resolve(
        environment.targetIndex,
        environment.workspaceEntries,
        entry,
        environment.resolverOptions
      ).also { session?.put(entry, it) }
      recordProblems(scopeName(entry), result.toProblemList())
      selector.add(result)
    }

    environment.libraryBundles.forEach { bundle ->
      selector.registerSupplemental(bundle.bsn, bundle.version, bundle.location, bundle.isWorkspace)
    }

    environment.requiredStartupBundles.forEach { bsn ->
      if (!selector.contains(bsn)) {
        val rb = environment.targetIndex.get(bsn)
        if (rb != null) {
          selector.registerSupplemental(bsn, rb.manifest.bundleVersion, rb.location, false)
        } else {
          recordProblems(
            scope = "Launch Plan",
            items = listOf(
              ResolveProblem(
                type = ResolveProblemType.MISSING_BUNDLE,
                symbol = bsn,
                message = "startup-level"
              )
            )
          )
        }
      }
    }

    val bundles = selector.entries().map { rb ->
      val level = levelFor(rb.bsn)
      val auto = autoStartFor(rb.bsn, level)
      startupLevels[rb.bsn] = level
      BundleStartSpec(
        bsn = rb.bsn,
        version = rb.version,
        location = rb.path,
        startLevel = level,
        autoStart = auto,
        isWorkspace = rb.isWorkspace
      )
    }
    val plan = LauncherPlan(
      bundles = bundles,
      framework = bundles.firstOrNull { it.bsn == options.frameworkBSN },
      properties = buildMap {
        options.product?.takeIf { it.isNotBlank() }?.let { put("eclipse.product", it) }
        options.application.takeIf { !it.isNullOrBlank() }?.let { put("eclipse.application", it) }
        putIfAbsent("osgi.bundles.defaultStartLevel", options.defaultStartLevel.toString())
      }
    )
    val context = LaunchContext(
      startupLevels = startupLevels,
      devProperties = environment.devProperties
    )

    return PlanResult(
      plan = plan,
      context = context,
      problemsByScope = problems.mapValues { it.value.toList() },
      selectedBundles = selector.entries()
    )
  }
}
