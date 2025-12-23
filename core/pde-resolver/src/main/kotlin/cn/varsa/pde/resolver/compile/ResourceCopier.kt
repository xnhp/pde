package cn.varsa.pde.resolver.compile

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.relativeTo

/**
 * Minimal PDE-like resource copy honoring bin.includes/bin.excludes globs.
 * Does not yet handle linked jars or source bundles; adequate for workspace dry-runs.
 */
interface ResourceCopier {
  fun copy(
    root: Path,
    outDir: Path,
    includes: List<String>,
    excludes: List<String>,
    classpathEntries: List<String> = emptyList()
  )
}

object DefaultResourceCopier : ResourceCopier {
  override fun copy(
    root: Path,
    outDir: Path,
    includes: List<String>,
    excludes: List<String>,
    classpathEntries: List<String>
  ) {
    val effIncludes = if (includes.isEmpty()) listOf(".") else includes
    val includeMatchers = buildMatchers(root, effIncludes)
    val excludeMatchers = buildMatchers(root, excludes)
    val normalizedOut = outDir.toAbsolutePath().normalize()

    Files.walk(root).use { stream ->
      stream
        .filter { Files.isRegularFile(it) && !it.toString().endsWith(".java") }
        .filter { file -> !file.toAbsolutePath().normalize().startsWith(normalizedOut) }
        .forEach { file ->
          val rel = runCatching { file.relativeTo(root) }.getOrNull() ?: return@forEach
          if (includeMatchers.none { it.matches(rel) }) return@forEach
          if (excludeMatchers.any { it.matches(rel) }) return@forEach
          val target = outDir.resolve(rel)
          target.parent?.createDirectories()
          Files.copy(file, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING)
        }
    }

    // Copy embedded Bundle-ClassPath entries that live inside the bundle (e.g., lib/foo.jar)
    classpathEntries.forEach { entry ->
      val path = Path.of(entry).toAbsolutePath().normalize()
      if (!path.startsWith(root.toAbsolutePath().normalize())) return@forEach
      if (!Files.exists(path) || Files.isDirectory(path)) return@forEach
      val rel = runCatching { path.relativeTo(root) }.getOrNull() ?: return@forEach
      val target = outDir.resolve(rel)
      if (Files.exists(target)) return@forEach
      target.parent?.createDirectories()
      Files.copy(path, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING)
    }
  }

  private fun normalizePattern(pattern: String): String =
    when {
      pattern == "." || pattern == "./" -> "**"
      pattern.endsWith("/") -> "$pattern**"
      else -> pattern
    }

  private fun buildMatchers(root: Path, patterns: List<String>): List<java.nio.file.PathMatcher> =
    patterns.flatMap { pattern ->
      val normalized = normalizePattern(pattern)
      val alts = if (normalized.startsWith("**/")) listOf(normalized.removePrefix("**/")) else emptyList()
      listOf(normalized) + alts
    }.map { pat -> root.fileSystem.getPathMatcher("glob:$pat") }
}
