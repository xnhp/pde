package cn.varsa.pde.resolver.compile

import cn.varsa.pde.resolver.workspace.WorkspaceDefaults
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes
import kotlin.io.path.createDirectories
import kotlin.io.path.exists

data class BundleCompileResult(
  val bsn: String,
  val success: Boolean,
  val output: String,
  val durationMillis: Long,
  val skipped: Boolean,
  /** Advisory diagnostics that do not fail the bundle; reported alongside the result. */
  val warnings: List<String> = emptyList()
)

object CompileExecutor {
  data class BundleCompilePlan(
    val bsn: String,
    val action: BundleCompileAction,
    val reason: String,
    val isWorkspace: Boolean
  )

  fun plan(
    specs: List<CompileSpec>
  ): List<BundleCompilePlan> {
    val plans = buildPlans(specs)
    return plans.map { plan ->
      BundleCompilePlan(
        bsn = plan.spec.bsn,
        action = plan.action,
        reason = plan.reason,
        isWorkspace = plan.spec.isWorkspace
      )
    }
  }

  fun compile(
    specs: List<CompileSpec>,
    compiler: CompilerPort = EcjCompiler(),
    resourceCopier: ResourceCopier = DefaultResourceCopier
  ): List<BundleCompileResult> =
    compileBundles(specs, compiler, resourceCopier)

  enum class BundleCompileAction { FULL, TARGET_SKIP }

  private data class BundlePlan(
    val spec: CompileSpec,
    val outDir: Path,
    val action: BundleCompileAction,
    val reason: String,
    val cleanOutput: Boolean
  )

  private fun compileBundles(
    specs: List<CompileSpec>,
    compiler: CompilerPort,
    resourceCopier: ResourceCopier
  ): List<BundleCompileResult> {
    val plans = buildPlans(specs)
    return plans.map { plan ->
      when (plan.action) {
        BundleCompileAction.TARGET_SKIP -> BundleCompileResult(
          plan.spec.bsn,
          success = true,
          output = "Target-platform bundle; compile skipped.",
          durationMillis = 0,
          skipped = true
        )
        BundleCompileAction.FULL -> {
          val startedAt = System.nanoTime()
          if (plan.cleanOutput) {
            cleanOutputDirectory(plan.outDir)
          }
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
          }
          val durationMillis = (System.nanoTime() - startedAt) / 1_000_000
          result.copy(durationMillis = durationMillis)
        }
      }
    }
  }

  private fun buildPlans(
    specs: List<CompileSpec>
  ): List<BundlePlan> {
    return specs.map { spec ->
      if (!spec.isWorkspace) {
        BundlePlan(spec, Path.of("."), BundleCompileAction.TARGET_SKIP, "Target bundle", cleanOutput = false)
      } else {
        val bundleRoot = Path.of(spec.bundlePath)
        val outDir = spec.outputDirectory?.let { Path.of(it) }
          ?: bundleRoot.resolve(WorkspaceDefaults.DEFAULT_OUTPUT_DIR)
        BundlePlan(spec, outDir, BundleCompileAction.FULL, "Full recompile", cleanOutput = true)
      }
    }
  }

  private fun cleanOutputDirectory(outDir: Path) {
    if (!outDir.exists()) return
    Files.walkFileTree(
      outDir,
      object : SimpleFileVisitor<Path>() {
        override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
          Files.deleteIfExists(file)
          return FileVisitResult.CONTINUE
        }

        override fun postVisitDirectory(dir: Path, exc: java.io.IOException?): FileVisitResult {
          Files.deleteIfExists(dir)
          return FileVisitResult.CONTINUE
        }
      }
    )
  }
}
