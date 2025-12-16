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
  fun copy(root: Path, outDir: Path, includes: List<String>, excludes: List<String>)
}

object DefaultResourceCopier : ResourceCopier {
  override fun copy(root: Path, outDir: Path, includes: List<String>, excludes: List<String>) {
    if (includes.isEmpty()) return
    val includeMatchers = includes.map { pattern -> root.fileSystem.getPathMatcher("glob:${normalizePattern(pattern)}") }
    val excludeMatchers = excludes.map { pattern -> root.fileSystem.getPathMatcher("glob:${normalizePattern(pattern)}") }
    Files.walk(root).use { stream ->
      stream.filter { Files.isRegularFile(it) && !it.toString().endsWith(".java") }.forEach { file ->
        val rel = runCatching { file.relativeTo(root) }.getOrNull() ?: return@forEach
        if (includeMatchers.none { it.matches(rel) }) return@forEach
        if (excludeMatchers.any { it.matches(rel) }) return@forEach
        val target = outDir.resolve(rel)
        target.parent?.createDirectories()
        Files.copy(file, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING)
      }
    }
  }

  private fun normalizePattern(pattern: String): String =
    if (pattern.endsWith("/")) "$pattern**" else pattern
}
