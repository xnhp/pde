package cn.varsa.pde.resolver.compile

import cn.varsa.pde.resolver.workspace.WorkspaceDefaults
import java.nio.file.Path
import kotlin.io.path.createDirectories

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
    resourceCopier: ResourceCopier = DefaultResourceCopier
  ): List<BundleCompileResult> =
    specs.map { compileBundle(it, compiler, resourceCopier) }

  private fun compileBundle(
    spec: CompileSpec,
    compiler: CompilerPort,
    resourceCopier: ResourceCopier
  ): BundleCompileResult {
    if (!spec.isWorkspace) {
      return BundleCompileResult(
        spec.bsn,
        success = true,
        output = "Target-platform bundle; compile skipped.",
        durationMillis = 0,
        skipped = true
      )
    }
    val bundleRoot = Path.of(spec.bundlePath)
    val outDir = spec.outputDirectory?.let { Path.of(it) }
      ?: bundleRoot.resolve(WorkspaceDefaults.DEFAULT_OUTPUT_DIR)
    outDir.createDirectories()

    val startedAt = System.nanoTime()
    val result = compiler.compile(spec.copy(outputDirectory = outDir.toString()))
    if (result.success && !result.skipped) {
      resourceCopier.copy(
        bundleRoot,
        outDir,
        spec.resourceIncludes,
        spec.resourceExcludes,
        spec.classpath,
        spec.sourceRoots
      )
    }
    val durationMillis = (System.nanoTime() - startedAt) / 1_000_000
    return result.copy(durationMillis = durationMillis)
  }
}
