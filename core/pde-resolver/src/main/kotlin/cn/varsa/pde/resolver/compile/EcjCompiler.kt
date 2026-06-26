package cn.varsa.pde.resolver.compile

import org.eclipse.jdt.internal.compiler.batch.Main
import java.io.ByteArrayOutputStream
import java.io.PrintWriter
import java.nio.file.Files
import cn.varsa.pde.resolver.workspace.WorkspaceDefaults
import java.nio.file.Path
import java.util.jar.JarFile
import java.util.logging.Logger
import kotlin.io.path.exists
import kotlin.io.path.isDirectory

class EcjCompiler(
  private val failOnProcessors: Boolean = true
) : CompilerPort {

  private val logger = Logger.getLogger(EcjCompiler::class.java.name)

  override fun compile(spec: CompileSpec): BundleCompileResult {
    val bundleRoot = Path.of(spec.bundlePath)
    val outDir = spec.outputDirectory?.let { Path.of(it) }
      ?: bundleRoot.resolve(WorkspaceDefaults.DEFAULT_OUTPUT_DIR)

    val sourceRoots = spec.sourceRoots
      .map { Path.of(it) }
      .filter { it.exists() && it.isDirectory() }
    val allSources = sourceRoots
      .flatMap { root -> Files.walk(root).filter { p -> p.toString().endsWith(".java") }.toList() }
    val (lockFiles, sources) = allSources.partition { path ->
      path.fileName?.toString()?.startsWith(".#") == true
    }
    if (lockFiles.isNotEmpty()) {
      val preview = lockFiles.take(3).joinToString(", ") { it.toString() }
      val suffix = if (lockFiles.size > 3) " ... and ${lockFiles.size - 3} more" else ""
      logger.info("Ignoring ${lockFiles.size} editor lock file(s) from compilation: $preview$suffix")
    }

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

    val classpath = spec.classpath.filter { it.isNotBlank() }
    val sourceLevel = compilerSourceLevel(spec) ?: "17"
    val targetLevel = compilerTargetLevel(spec, sourceLevel)
    var classfileWarning: String? = null
    val classfileMajor = ClassfileVersionChecker.classMajorForLevel(targetLevel)
    if (classfileMajor != null) {
      val targetVersion = ClassfileVersionChecker.javaVersionForLevel(targetLevel)
      val mismatches = ClassfileVersionChecker.findMismatches(classpath, classfileMajor, targetVersion)
      if (mismatches.isNotEmpty()) {
        classfileWarning = buildString {
          append("WARNING: Classpath contains class files requiring a newer Java version than the bundle target.\n")
          append("Target: Java ").append(targetLevel)
          spec.executionEnvironment?.let { append(" (BREE ").append(it).append(")") }
          append("; max classfile version ").append(classfileMajor).append(".\n")
          mismatches.take(2).forEach { mismatch ->
            append("- ")
              .append(mismatch.classpathEntry)
              .append(": ")
              .append(mismatch.classFile)
              .append(" (major ")
              .append(mismatch.majorVersion)
              .append(")\n")
          }
          if (mismatches.size > 2) {
            append("... and ").append(mismatches.size - 2).append(" more\n")
          }
          append("Use a target platform built for Java ")
            .append(targetLevel)
            .append(" or update the bundle BREE/compile prefs to match the target.")
        }
      }
    }

    val args = assembleArgs(spec, outDir.toString(), sources.map { it.toString() }, sourceLevel, targetLevel)

    val baos = ByteArrayOutputStream()
    val writer = PrintWriter(baos, true)
    val compiler = Main(writer, writer, false, null, null)
    val success = compiler.compile(args.toTypedArray())

    val output = buildString {
      classfileWarning?.let {
        append(it.trimEnd())
        append("\n")
      }
      append(baos.toString())
    }

    return BundleCompileResult(
      spec.bsn,
      success = success,
      output = output,
      durationMillis = 0,
      skipped = false
    )
  }

  companion object {
    internal fun compilerSourceLevel(spec: CompileSpec): String? =
      spec.compilerPrefs["org.eclipse.jdt.core.compiler.source"]
        ?: spec.executionEnvironment?.let(CompilerLevels::levelFromExecutionEnvironment)

    internal fun compilerTargetLevel(spec: CompileSpec, fallback: String): String =
      spec.compilerPrefs["org.eclipse.jdt.core.compiler.codegen.targetPlatform"]
        ?: spec.compilerPrefs["org.eclipse.jdt.core.compiler.compliance"]
        ?: spec.executionEnvironment?.let(CompilerLevels::levelFromExecutionEnvironment)
        ?: fallback

    internal fun debugFlag(spec: CompileSpec): String? {
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

    /** Append `--add-exports`/`--add-opens` flags, one flag+value pair per token. */
    internal fun appendModuleAccessFlags(
      args: MutableList<String>,
      addExports: List<String>,
      addOpens: List<String>
    ) {
      addExports.filter { it.isNotBlank() }.forEach { args += listOf("--add-exports", it) }
      addOpens.filter { it.isNotBlank() }.forEach { args += listOf("--add-opens", it) }
    }

    /** Assemble the ECJ batch argument list. Module-access flags precede the source files. */
    internal fun assembleArgs(
      spec: CompileSpec,
      outDir: String,
      sources: List<String>,
      sourceLevel: String = compilerSourceLevel(spec) ?: "17",
      targetLevel: String = compilerTargetLevel(spec, sourceLevel)
    ): List<String> {
      val args = mutableListOf<String>()
      args += listOf("-d", outDir)

      val classpath = spec.classpath.filter { it.isNotBlank() }
      if (classpath.isNotEmpty()) {
        args += listOf("-classpath", classpath.joinToString(System.getProperty("path.separator")))
      }

      args += listOf("-source", sourceLevel, "-target", targetLevel)
      args += listOf("-encoding", "UTF-8")
      args += listOf("-proc:none") // processors are unsupported for now
      debugFlag(spec)?.let { args += it }

      appendModuleAccessFlags(args, spec.addExports, spec.addOpens)

      args += sources
      return args
    }
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
