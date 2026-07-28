package cn.varsa.pde.launch

import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes
import java.util.logging.Logger

/** Directories that are never worth descending into while looking for lib/fetch_jars folders. */
private val fetchJarsScanSkipDirs = setOf(".git", "node_modules", "bin", "target")

/**
 * Finds `fetch_jars` directories (possibly nested, e.g. `lib/mysql8/fetch_jars`), regardless of
 * whether they currently contain a Maven pom.xml.
 */
internal fun discoverFetchJarsDirs(bundlePath: Path): List<Path> {
  if (!Files.isDirectory(bundlePath)) return emptyList()

  val fetchJarsDirs = mutableListOf<Path>()
  Files.walkFileTree(bundlePath, object : SimpleFileVisitor<Path>() {
    override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult {
      val name = dir.fileName?.toString().orEmpty()
      if (dir != bundlePath && name in fetchJarsScanSkipDirs) return FileVisitResult.SKIP_SUBTREE
      if (name == "fetch_jars") {
        fetchJarsDirs.add(dir)
        return FileVisitResult.SKIP_SUBTREE
      }
      return FileVisitResult.CONTINUE
    }
  })

  return fetchJarsDirs
}

/**
 * Finds `fetch_jars` directories that ship a Maven pom.xml, i.e. that the `pde fetch-jars`
 * helper can run `mvn clean package` against.
 */
internal fun discoverRunnableFetchJarsDirs(bundlePath: Path): List<Path> =
  discoverFetchJarsDirs(bundlePath).filter { Files.isRegularFile(it.resolve("pom.xml")) }

/**
 * Finds `lib`/`libs` directories (possibly nested, e.g. `lib/mysql8/fetch_jars`) that ship a
 * `fetch_jars` helper but currently contain no jars, meaning the helper has not been run yet.
 */
internal fun findEmptyFetchJarsLibDirs(bundlePath: Path): List<Path> {
  return discoverFetchJarsDirs(bundlePath).mapNotNull { it.parent }.distinct().filter { libDir -> !containsJar(libDir) }
}

private fun containsJar(dir: Path): Boolean =
  Files.list(dir).use { children -> children.anyMatch { Files.isRegularFile(it) && it.fileName.toString().endsWith(".jar") } }

internal fun warnOnEmptyFetchJarsLibs(bundlePath: Path, logger: Logger) {
  findEmptyFetchJarsLibDirs(bundlePath).forEach { libDir ->
    logger.warning("No jars present in ${libDir}; run the fetch_jars helper in ${libDir.resolve("fetch_jars")} before compiling/analyzing")
  }
}
