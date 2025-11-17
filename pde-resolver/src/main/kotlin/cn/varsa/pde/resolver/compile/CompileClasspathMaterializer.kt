package cn.varsa.pde.resolver.compile

import cn.varsa.pde.resolver.index.ResolvedBundle
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.Locale
import java.util.jar.JarFile

data class CompileClasspathMaterializerOptions(
  val extractionRoot: Path
)

data class MaterializeResult(
  val classpath: List<Path>,
  val workspaceDependencies: Set<String>,
  val problems: List<CompileClasspathProblem>
)

object CompileClasspathMaterializer {
  fun materialize(
    result: CompileClasspathResult,
    options: CompileClasspathMaterializerOptions
  ): MaterializeResult {
    val extractionRoot = options.extractionRoot.toAbsolutePath().normalize()
    Files.createDirectories(extractionRoot)

    val classpath: MutableList<Path> = mutableListOf()
    val problems: MutableList<CompileClasspathProblem> = result.problems.toMutableList()
    val extracted = hashMapOf<Pair<Path, String>, Path>()

    result.entries.forEach { entry ->
      when (entry) {
        is CompileClasspathEntry.ModulePath -> classpath.add(entry.path.normalize())
        is CompileClasspathEntry.WorkspaceResource ->
          classpath.add(entry.descriptor.path.resolve(entry.entryPath).normalize())
        is CompileClasspathEntry.TargetBundle ->
          resolveTargetEntry(entry, extractionRoot, extracted, problems)?.let { classpath.add(it) }
      }
    }

    return MaterializeResult(
      classpath = classpath,
      workspaceDependencies = result.workspaceDependencies,
      problems = problems
    )
  }

  private fun resolveTargetEntry(
    entry: CompileClasspathEntry.TargetBundle,
    extractionRoot: Path,
    extractedCache: MutableMap<Pair<Path, String>, Path>,
    problems: MutableList<CompileClasspathProblem>
  ): Path? {
    val bundle = entry.bundle
    val entryPath = entry.entryPath ?: return bundle.location.normalize()

    if (bundle.isDirectory) {
      return bundle.location.resolve(entryPath).normalize()
    }

    val cacheKey = bundle.location to entryPath
    extractedCache[cacheKey]?.let { return it }

    return JarFile(bundle.location.toFile()).use { jarFile ->
      val jarEntry = jarFile.getJarEntry(entryPath)
      if (jarEntry == null) {
        problems.add(
          CompileClasspathProblem(
            rawEntry = "platform:/plugin/${bundle.manifest.bundleSymbolicName?.key ?: bundle.location.fileName}${if (entryPath.isEmpty()) "" else "/$entryPath"}",
            type = CompileClasspathProblemType.MISSING_BUNDLE,
            message = "Entry '$entryPath' not found inside bundle ${bundle.location}"
          )
        )
        return@use null
      }
      val sanitizedName = sanitizeExtractName(bundle, entryPath)
      val target = extractionRoot.resolve(sanitizedName)
      target.parent?.let { Files.createDirectories(it) }
      jarFile.getInputStream(jarEntry).use { input ->
        Files.copy(input, target, StandardCopyOption.REPLACE_EXISTING)
      }
      extractedCache[cacheKey] = target
      target
    }
  }

  private fun sanitizeExtractName(bundle: ResolvedBundle, entryPath: String): String {
    val bundleId = bundle.manifest.bundleSymbolicName?.key ?: bundle.location.fileName?.toString() ?: "bundle"
    val raw = "${bundleId}_${entryPath}"
    val sanitized = buildString(raw.length) {
      raw.forEach { ch ->
        append(
          when {
            ch.isLetterOrDigit() -> ch
            ch == '.' || ch == '_' || ch == '-' -> ch
            else -> '_'
          }
        )
      }
    }
    return sanitized.lowercase(Locale.US)
  }
}
