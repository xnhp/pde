package cn.varsa.pde.resolver.compile

import org.eclipse.jdt.internal.compiler.batch.Main
import java.io.ByteArrayOutputStream
import java.io.PrintWriter
import java.nio.file.Files
import java.nio.file.Path
import java.util.jar.JarFile
import kotlin.io.path.exists
import kotlin.io.path.isDirectory

class EcjCompiler(
  private val failOnProcessors: Boolean = true
) : CompilerPort {

  override fun compile(spec: CompileSpec): BundleCompileResult {
    val bundleRoot = Path.of(spec.bundlePath)
    val outDir = spec.outputDirectory?.let { Path.of(it) } ?: bundleRoot.resolve("out/production")

    val sources = spec.sourceRoots
      .map { Path.of(it) }
      .filter { it.exists() && it.isDirectory() }
      .flatMap { root -> Files.walk(root).filter { p -> p.toString().endsWith(".java") }.toList() }

    if (sources.isEmpty()) {
      return BundleCompileResult(spec.bsn, success = true, output = "No sources; skipped")
    }

    val processorReasons = detectAnnotationProcessors(bundleRoot, spec.classpath)
    if (failOnProcessors && processorReasons.isNotEmpty()) {
      val msg = buildString {
        append("Annotation processors detected; javac fallback not implemented.\n")
        processorReasons.forEach { append("- ").append(it).append('\n') }
      }
      return BundleCompileResult(spec.bsn, success = false, output = msg.trimEnd())
    }

    val args = mutableListOf<String>()
    args += listOf("-d", outDir.toString())

    val classpath = spec.classpath.filter { it.isNotBlank() }
    if (classpath.isNotEmpty()) {
      args += listOf("-classpath", classpath.joinToString(System.getProperty("path.separator")))
    }

    val sourceLevel = compilerSourceLevel(spec) ?: "17"
    val targetLevel = compilerTargetLevel(spec, sourceLevel)
    args += listOf("-source", sourceLevel, "-target", targetLevel)
    args += listOf("-encoding", "UTF-8")
    args += listOf("-proc:none") // processors are unsupported for now

    args += sources.map { it.toString() }

    val baos = ByteArrayOutputStream()
    val writer = PrintWriter(baos, true)
    val compiler = Main(writer, writer, false, null, null)
    val success = compiler.compile(args.toTypedArray())

    return BundleCompileResult(spec.bsn, success = success, output = baos.toString())
  }

  private fun compilerSourceLevel(spec: CompileSpec): String? =
    spec.compilerPrefs["org.eclipse.jdt.core.compiler.source"]
      ?: spec.executionEnvironment?.let(::levelFromExecutionEnvironment)

  private fun compilerTargetLevel(spec: CompileSpec, fallback: String): String =
    spec.compilerPrefs["org.eclipse.jdt.core.compiler.codegen.targetPlatform"]
      ?: spec.compilerPrefs["org.eclipse.jdt.core.compiler.compliance"]
      ?: spec.executionEnvironment?.let(::levelFromExecutionEnvironment)
      ?: fallback

  private fun levelFromExecutionEnvironment(ee: String): String? =
    ee.substringAfterLast('-', missingDelimiterValue = ee)
      .removePrefix("1.").let { part ->
        when {
          part.equals("J2SE", ignoreCase = true) -> null
          part.matches(Regex("\\d+(\\.\\d+)?")) -> ee.substringAfterLast('-').removePrefix("1.")
          ee.equals("J2SE-1.5", ignoreCase = true) -> "5"
          else -> null
        }
      }?.let { if (it.contains('.')) it else it }

  private fun detectAnnotationProcessors(bundleRoot: Path, classpath: List<String>): List<String> {
    val reasons = mutableListOf<String>()
    if (Files.exists(bundleRoot.resolve(".factorypath")) || Files.exists(bundleRoot.resolve("factorypath.xml"))) {
      reasons += "factorypath file present in bundle root"
    }
    classpath.forEach { entry ->
      val path = Path.of(entry)
      val name = path.fileName?.toString()?.lowercase() ?: ""
      if (name.contains("lombok")) {
        reasons += "lombok detected on classpath ($entry)"
        return@forEach
      }
      if (path.toFile().isFile && name.endsWith(".jar")) {
        runCatching {
          JarFile(path.toFile()).use { jar ->
            val service = jar.getEntry("META-INF/services/javax.annotation.processing.Processor")
            if (service != null) {
              reasons += "annotation processors declared in $entry"
            }
          }
        }
      }
    }
    return reasons
  }
}
