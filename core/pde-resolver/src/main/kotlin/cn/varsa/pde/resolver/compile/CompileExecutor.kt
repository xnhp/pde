package cn.varsa.pde.resolver.compile

import java.nio.file.Path
import kotlin.io.path.createDirectories

data class BundleCompileResult(
  val bsn: String,
  val success: Boolean,
  val output: String
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
    val bundleRoot = Path.of(spec.bundlePath)
    val outDir = spec.outputDirectory?.let { Path.of(it) } ?: bundleRoot.resolve("bin")
    outDir.createDirectories()

    val result = compiler.compile(spec.copy(outputDirectory = outDir.toString()))
    if (result.success) {
      resourceCopier.copy(
        bundleRoot,
        outDir,
        spec.resourceIncludes,
        spec.resourceExcludes,
        spec.classpath
      )
    }
    return result
  }
}
