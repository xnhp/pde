package cn.varsa.pde.resolver.compile

import org.eclipse.jdt.internal.compiler.batch.Main
import java.io.ByteArrayOutputStream
import java.io.PrintWriter
import java.nio.file.Files
import cn.varsa.pde.resolver.workspace.WorkspaceDefaults
import java.nio.file.Path
import java.util.jar.JarFile
import kotlin.io.path.exists
import kotlin.io.path.isDirectory

class EcjCompiler(
  private val failOnProcessors: Boolean = true
) : CompilerPort {

  override fun compile(spec: CompileSpec): BundleCompileResult {
    val bundleRoot = Path.of(spec.bundlePath)
    val outDir = spec.outputDirectory?.let { Path.of(it) }
      ?: bundleRoot.resolve(WorkspaceDefaults.DEFAULT_OUTPUT_DIR)

    val sources = spec.sourceRoots
      .map { Path.of(it) }
      .filter { it.exists() && it.isDirectory() }
      .flatMap { root -> Files.walk(root).filter { p -> p.toString().endsWith(".java") }.toList() }

    if (sources.isEmpty()) {
      return BundleCompileResult(
        spec.bsn,
        success = true,
        output = "No sources; skipped",
        durationMillis = 0,
        skipped = true
      )
    }

    val processorReasons = detectAnnotationProcessors(bundleRoot, spec.classpath)
    if (failOnProcessors && processorReasons.isNotEmpty()) {
      val msg = buildString {
        append("Annotation processors detected; javac fallback not implemented.\n")
        processorReasons.forEach { append("- ").append(it).append('\n') }
      }
      return BundleCompileResult(
        spec.bsn,
        success = false,
        output = msg.trimEnd(),
        durationMillis = 0,
        skipped = false
      )
    }

    val args = mutableListOf<String>()
    args += listOf("-d", outDir.toString())

    val classpath = spec.classpath.filter { it.isNotBlank() }
    if (classpath.isNotEmpty()) {
      args += listOf("-classpath", classpath.joinToString(System.getProperty("path.separator")))
    }

    val sourceLevel = compilerSourceLevel(spec) ?: "17"
    val targetLevel = compilerTargetLevel(spec, sourceLevel)
    val classfileMajor = ClassfileVersionChecker.classMajorForLevel(targetLevel)
    if (classfileMajor != null) {
      val mismatches = ClassfileVersionChecker.findMismatches(classpath, classfileMajor)
      if (mismatches.isNotEmpty()) {
        val msg = buildString {
          append("Classpath contains class files requiring a newer Java version than the bundle target.\n")
          append("Target: Java ").append(targetLevel)
          spec.executionEnvironment?.let { append(" (BREE ").append(it).append(")") }
          append("; max classfile version ").append(classfileMajor).append(".\n")
          mismatches.take(10).forEach { mismatch ->
            append("- ")
              .append(mismatch.classpathEntry)
              .append(": ")
              .append(mismatch.classFile)
              .append(" (major ")
              .append(mismatch.majorVersion)
              .append(")\n")
          }
          if (mismatches.size > 10) {
            append("... and ").append(mismatches.size - 10).append(" more\n")
          }
          append("Use a target platform built for Java ")
            .append(targetLevel)
            .append(" or update the bundle BREE/compile prefs to match the target.")
        }
        return BundleCompileResult(
          spec.bsn,
          success = false,
          output = msg.trimEnd(),
          durationMillis = 0,
          skipped = false
        )
      }
    }
    args += listOf("-source", sourceLevel, "-target", targetLevel)
    args += listOf("-encoding", "UTF-8")
    args += listOf("-proc:none") // processors are unsupported for now
    debugFlag(spec)?.let { args += it }

    args += sources.map { it.toString() }

    val baos = ByteArrayOutputStream()
    val writer = PrintWriter(baos, true)
    val compiler = Main(writer, writer, false, null, null)
    val success = compiler.compile(args.toTypedArray())

    return BundleCompileResult(
      spec.bsn,
      success = success,
      output = baos.toString(),
      durationMillis = 0,
      skipped = false
    )
  }

  private fun compilerSourceLevel(spec: CompileSpec): String? =
    spec.compilerPrefs["org.eclipse.jdt.core.compiler.source"]
      ?: spec.executionEnvironment?.let(CompilerLevels::levelFromExecutionEnvironment)

  private fun compilerTargetLevel(spec: CompileSpec, fallback: String): String =
    spec.compilerPrefs["org.eclipse.jdt.core.compiler.codegen.targetPlatform"]
      ?: spec.compilerPrefs["org.eclipse.jdt.core.compiler.compliance"]
      ?: spec.executionEnvironment?.let(CompilerLevels::levelFromExecutionEnvironment)
      ?: fallback

  private fun debugFlag(spec: CompileSpec): String? {
    val line = spec.compilerPrefs["org.eclipse.jdt.core.compiler.debug.lineNumber"] == "generate"
    val vars = spec.compilerPrefs["org.eclipse.jdt.core.compiler.debug.localVariable"] == "generate"
    val source = spec.compilerPrefs["org.eclipse.jdt.core.compiler.debug.sourceFile"] == "generate"
    if (!line && !vars && !source) return null
    if (line && vars && source) return "-g"
    val parts = buildList {
      if (line) add("lines")
      if (vars) add("vars")
      if (source) add("source")
    }
    return "-g:" + parts.joinToString(",")
  }

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
