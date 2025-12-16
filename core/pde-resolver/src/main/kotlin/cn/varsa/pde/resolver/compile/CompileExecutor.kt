package cn.varsa.pde.resolver.compile

import org.eclipse.jdt.internal.compiler.batch.Main
import java.io.ByteArrayOutputStream
import java.io.PrintWriter
import java.nio.file.Files
import java.nio.file.Path
import java.util.*
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.isDirectory
import kotlin.io.path.relativeTo

data class BundleCompileResult(
  val bsn: String,
  val success: Boolean,
  val output: String
)

object CompileExecutor {
  fun compile(
    specs: List<CompileSpec>,
    failOnProcessors: Boolean = true,
    resourceCopier: ResourceCopier = DefaultResourceCopier
  ): List<BundleCompileResult> =
    specs.map { compileBundle(it, failOnProcessors, resourceCopier) }

  private fun compileBundle(
    spec: CompileSpec,
    failOnProcessors: Boolean,
    resourceCopier: ResourceCopier
  ): BundleCompileResult {
    val bundleRoot = Path.of(spec.bundlePath)
    val outDir = spec.outputDirectory?.let { Path.of(it) } ?: bundleRoot.resolve("bin")
    outDir.createDirectories()

    // Detect annotation processors (very conservative: look for factorypath or prefs)
    if (failOnProcessors && hasAnnotationProcessors(bundleRoot)) {
      return BundleCompileResult(spec.bsn, success = false, output = "Annotation processors detected; javac fallback not implemented.")
    }

    val sources = spec.sourceRoots
      .map { Path.of(it) }
      .filter { it.exists() && it.isDirectory() }
      .flatMap { Files.walk(it).filter { p -> p.toString().endsWith(".java") }.toList() }

    if (sources.isEmpty()) {
      return BundleCompileResult(spec.bsn, success = true, output = "No sources; skipped")
    }

    val args = mutableListOf<String>()
    args += listOf("-d", outDir.toString())
    val cp = spec.classpath.joinToString(System.getProperty("path.separator"))
    if (cp.isNotBlank()) {
      args += listOf("-classpath", cp)
    }
    val sourceLevel = spec.compilerPrefs["org.eclipse.jdt.core.compiler.source"] ?: "17"
    val targetLevel = spec.compilerPrefs["org.eclipse.jdt.core.compiler.codegen.targetPlatform"] ?: sourceLevel
    args += listOf("-source", sourceLevel, "-target", targetLevel)
    args += listOf("-proc:none") // fail-fast for processors; revisit with yarrow-thunder
    args += sources.map { it.toString() }

    val baos = ByteArrayOutputStream()
    val writer = PrintWriter(baos, true)
    val compiler = Main(writer, writer, false, null, null)
    val success = compiler.compile(args.toTypedArray())

    resourceCopier.copy(bundleRoot, outDir, spec.resourceIncludes, spec.resourceExcludes)

    return BundleCompileResult(spec.bsn, success = success, output = baos.toString())
  }

  private fun hasAnnotationProcessors(bundleRoot: Path): Boolean =
    Files.exists(bundleRoot.resolve(".factorypath")) ||
      Files.exists(bundleRoot.resolve("factorypath.xml")) ||
      false

}
