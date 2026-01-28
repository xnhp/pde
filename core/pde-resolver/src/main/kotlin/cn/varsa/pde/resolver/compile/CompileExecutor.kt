package cn.varsa.pde.resolver.compile

import cn.varsa.pde.resolver.workspace.WorkspaceDefaults
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.exists

data class BundleCompileResult(
  val bsn: String,
  val success: Boolean,
  val output: String,
  val durationMillis: Long,
  val skipped: Boolean
)

object CompileExecutor {
  fun compile(
    specs: List<CompileSpec>,
    compiler: CompilerPort = EcjCompiler(),
    resourceCopier: ResourceCopier = DefaultResourceCopier,
    cache: BundleCompileCache = BundleCompileCache.default(),
    workspaceDependencies: Map<String, Set<String>> = emptyMap(),
    forceFullRebuild: Boolean = false
  ): List<BundleCompileResult> =
    compileBundles(specs, compiler, resourceCopier, cache, workspaceDependencies, forceFullRebuild)

  private enum class BundleCompileAction { FULL, RESOURCES_ONLY, SKIP, TARGET_SKIP }

  private data class BundlePlan(
    val spec: CompileSpec,
    val outDir: Path,
    val fingerprint: BundleCompileFingerprint?,
    val action: BundleCompileAction,
    val reason: String
  )

  private fun compileBundles(
    specs: List<CompileSpec>,
    compiler: CompilerPort,
    resourceCopier: ResourceCopier,
    cache: BundleCompileCache,
    workspaceDependencies: Map<String, Set<String>>,
    forceFullRebuild: Boolean
  ): List<BundleCompileResult> {
    val plans = buildPlans(specs, cache, workspaceDependencies, forceFullRebuild)
    val results = plans.map { plan ->
      when (plan.action) {
        BundleCompileAction.TARGET_SKIP -> BundleCompileResult(
          plan.spec.bsn,
          success = true,
          output = "Target-platform bundle; compile skipped.",
          durationMillis = 0,
          skipped = true
        )
        BundleCompileAction.SKIP -> BundleCompileResult(
          plan.spec.bsn,
          success = true,
          output = plan.reason,
          durationMillis = 0,
          skipped = true
        )
        BundleCompileAction.RESOURCES_ONLY -> {
          val startedAt = System.nanoTime()
          plan.outDir.createDirectories()
          resourceCopier.copy(
            Path.of(plan.spec.bundlePath),
            plan.outDir,
            plan.spec.resourceIncludes,
            plan.spec.resourceExcludes,
            plan.spec.classpath,
            plan.spec.sourceRoots
          )
          plan.fingerprint?.let { fingerprint ->
            cache.put(
              BundleCompileCacheEntry(
                bsn = plan.spec.bsn,
                bundlePath = plan.spec.bundlePath,
                outputDirectory = fingerprint.outputDirectory,
                sourcesHash = fingerprint.sourcesHash,
                resourcesHash = fingerprint.resourcesHash,
                metadataHash = fingerprint.metadataHash,
                classpathHash = fingerprint.classpathHash,
                success = true
              )
            )
          }
          val durationMillis = (System.nanoTime() - startedAt) / 1_000_000
          BundleCompileResult(
            plan.spec.bsn,
            success = true,
            output = plan.reason,
            durationMillis = durationMillis,
            skipped = true
          )
        }
        BundleCompileAction.FULL -> {
          val startedAt = System.nanoTime()
          plan.outDir.createDirectories()
          val result = compiler.compile(plan.spec.copy(outputDirectory = plan.outDir.toString()))
          if (result.success) {
            resourceCopier.copy(
              Path.of(plan.spec.bundlePath),
              plan.outDir,
              plan.spec.resourceIncludes,
              plan.spec.resourceExcludes,
              plan.spec.classpath,
              plan.spec.sourceRoots
            )
            plan.fingerprint?.let { fingerprint ->
              cache.put(
                BundleCompileCacheEntry(
                  bsn = plan.spec.bsn,
                  bundlePath = plan.spec.bundlePath,
                  outputDirectory = fingerprint.outputDirectory,
                  sourcesHash = fingerprint.sourcesHash,
                  resourcesHash = fingerprint.resourcesHash,
                  metadataHash = fingerprint.metadataHash,
                  classpathHash = fingerprint.classpathHash,
                  success = true
                )
              )
            }
          }
          val durationMillis = (System.nanoTime() - startedAt) / 1_000_000
          result.copy(durationMillis = durationMillis)
        }
      }
    }
    cache.saveIfDirty()
    return results
  }

  private fun buildPlans(
    specs: List<CompileSpec>,
    cache: BundleCompileCache,
    workspaceDependencies: Map<String, Set<String>>,
    forceFullRebuild: Boolean
  ): List<BundlePlan> {
    val ignoredClasspathEntries = specs
      .filter { it.isWorkspace }
      .map { spec ->
        val bundleRoot = Path.of(spec.bundlePath)
        spec.outputDirectory?.let { Path.of(it) }
          ?: bundleRoot.resolve(WorkspaceDefaults.DEFAULT_OUTPUT_DIR)
      }
      .map { it.toAbsolutePath().normalize() }
      .toSet()

    val initialPlans = specs.map { spec ->
      if (!spec.isWorkspace) {
        BundlePlan(spec, Path.of("."), null, BundleCompileAction.TARGET_SKIP, "Target bundle")
      } else {
        val bundleRoot = Path.of(spec.bundlePath)
        val outDir = spec.outputDirectory?.let { Path.of(it) }
          ?: bundleRoot.resolve(WorkspaceDefaults.DEFAULT_OUTPUT_DIR)
        if (forceFullRebuild) {
          val fingerprint = BundleCompileHasher.fingerprint(spec, outDir, ignoredClasspathEntries)
          BundlePlan(spec, outDir, fingerprint, BundleCompileAction.FULL, "Forced full rebuild")
        } else {
          val fingerprint = BundleCompileHasher.fingerprint(spec, outDir, ignoredClasspathEntries)
          val cached = cache.get(spec.bsn)
          val decision = decideAction(spec, outDir, fingerprint, cached)
          BundlePlan(spec, outDir, fingerprint, decision.first, decision.second)
        }
      }
    }

    val dirty = initialPlans
      .filter { it.action == BundleCompileAction.FULL }
      .map { it.spec.bsn }
      .toMutableSet()
    if (dirty.isEmpty()) return initialPlans

    val reverseDeps = buildReverseDependencies(workspaceDependencies)
    val forcedReasons = mutableMapOf<String, String>()
    val queue = ArrayDeque<String>()
    dirty.forEach { queue.add(it) }
    while (queue.isNotEmpty()) {
      val changed = queue.removeFirst()
      reverseDeps[changed].orEmpty().forEach { dependent ->
        if (dependent !in dirty) {
          dirty += dependent
          forcedReasons[dependent] = "dependency changed: $changed"
          queue.add(dependent)
        }
      }
    }

    return initialPlans.map { plan ->
      if (!plan.spec.isWorkspace) return@map plan
      if (plan.spec.bsn !in dirty) return@map plan
      if (plan.action == BundleCompileAction.FULL) return@map plan
      val reason = forcedReasons[plan.spec.bsn] ?: "dependency changed"
      plan.copy(action = BundleCompileAction.FULL, reason = reason)
    }
  }

  private fun decideAction(
    spec: CompileSpec,
    outDir: Path,
    fingerprint: BundleCompileFingerprint,
    cached: BundleCompileCacheEntry?
  ): Pair<BundleCompileAction, String> {
    if (cached == null) return BundleCompileAction.FULL to "No incremental cache entry"
    if (cached.bundlePath != spec.bundlePath) return BundleCompileAction.FULL to "Bundle path changed"
    if (cached.outputDirectory != fingerprint.outputDirectory) return BundleCompileAction.FULL to "Output directory changed"
    if (!cached.success) return BundleCompileAction.FULL to "Previous compile failed"
    if (!outDir.exists()) return BundleCompileAction.FULL to "Output directory missing"
    if (cached.metadataHash != fingerprint.metadataHash) return BundleCompileAction.FULL to "Bundle metadata changed"
    if (cached.classpathHash != fingerprint.classpathHash) return BundleCompileAction.FULL to "Classpath changed"
    if (cached.sourcesHash != fingerprint.sourcesHash) return BundleCompileAction.FULL to "Source changes detected"
    if (cached.resourcesHash != fingerprint.resourcesHash) return BundleCompileAction.RESOURCES_ONLY to "Resources changed; compile skipped"
    return BundleCompileAction.SKIP to "Up-to-date; compile skipped"
  }

  private fun buildReverseDependencies(deps: Map<String, Set<String>>): Map<String, Set<String>> {
    val reverse = mutableMapOf<String, MutableSet<String>>()
    deps.forEach { (bundle, requires) ->
      requires.forEach { dep ->
        reverse.computeIfAbsent(dep) { mutableSetOf() }.add(bundle)
      }
    }
    return reverse
  }
}
