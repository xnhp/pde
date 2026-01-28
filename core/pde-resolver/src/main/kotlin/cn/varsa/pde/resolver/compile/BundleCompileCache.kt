package cn.varsa.pde.resolver.compile

import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.Properties
import kotlin.io.path.absolutePathString
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.isDirectory
import kotlin.io.path.isRegularFile
import kotlin.io.path.name
import kotlin.io.path.pathString
import kotlin.io.path.relativeTo

data class BundleCompileFingerprint(
  val sourcesHash: String,
  val resourcesHash: String,
  val metadataHash: String,
  val classpathHash: String,
  val outputDirectory: String
)

data class BundleCompileCacheEntry(
  val bsn: String,
  val bundlePath: String,
  val outputDirectory: String,
  val sourcesHash: String,
  val resourcesHash: String,
  val metadataHash: String,
  val classpathHash: String,
  val success: Boolean
)

class BundleCompileCache(private val cacheFile: Path) {
  companion object {
    private const val SCHEMA = "1"

    fun default(): BundleCompileCache = BundleCompileCache(defaultCacheFile())

    private fun defaultCacheFile(): Path {
      val base = System.getenv("XDG_CACHE_HOME")?.let { Path.of(it) }
        ?: Path.of(System.getProperty("user.home"), ".cache")
      val dir = base.resolve("pde-resolver/compile/v$SCHEMA")
      return dir.resolve("bundles.properties")
    }
  }

  private val props = Properties()
  private var loaded = false
  private var dirty = false

  private fun loadIfNeeded() {
    if (loaded) return
    loaded = true
    if (!Files.exists(cacheFile)) return
    FileInputStream(cacheFile.toFile()).use { props.load(it) }
  }

  fun get(bsn: String): BundleCompileCacheEntry? {
    loadIfNeeded()
    if (props.getProperty("schema") != null && props.getProperty("schema") != SCHEMA) return null
    val prefix = "bundle.$bsn."
    val bundlePath = props.getProperty(prefix + "path") ?: return null
    val outputDirectory = props.getProperty(prefix + "output") ?: return null
    val sourcesHash = props.getProperty(prefix + "sources") ?: return null
    val resourcesHash = props.getProperty(prefix + "resources") ?: return null
    val metadataHash = props.getProperty(prefix + "metadata") ?: return null
    val classpathHash = props.getProperty(prefix + "classpath") ?: return null
    val success = props.getProperty(prefix + "success")?.toBooleanStrictOrNull() ?: false
    return BundleCompileCacheEntry(
      bsn = bsn,
      bundlePath = bundlePath,
      outputDirectory = outputDirectory,
      sourcesHash = sourcesHash,
      resourcesHash = resourcesHash,
      metadataHash = metadataHash,
      classpathHash = classpathHash,
      success = success
    )
  }

  fun put(entry: BundleCompileCacheEntry) {
    loadIfNeeded()
    props["schema"] = SCHEMA
    val prefix = "bundle.${entry.bsn}."
    props[prefix + "path"] = entry.bundlePath
    props[prefix + "output"] = entry.outputDirectory
    props[prefix + "sources"] = entry.sourcesHash
    props[prefix + "resources"] = entry.resourcesHash
    props[prefix + "metadata"] = entry.metadataHash
    props[prefix + "classpath"] = entry.classpathHash
    props[prefix + "success"] = entry.success.toString()
    dirty = true
  }

  fun saveIfDirty() {
    if (!dirty) return
    cacheFile.parent?.createDirectories()
    val tmp = cacheFile.resolveSibling(cacheFile.fileName.toString() + ".tmp")
    FileOutputStream(tmp.toFile()).use { props.store(it, "pde-compile incremental cache") }
    if (!tmp.toFile().renameTo(cacheFile.toFile())) {
      FileOutputStream(cacheFile.toFile()).use { props.store(it, "pde-compile incremental cache") }
      tmp.toFile().delete()
    }
    dirty = false
  }
}

object BundleCompileHasher {
  fun fingerprint(spec: CompileSpec, outputDir: Path): BundleCompileFingerprint {
    val bundleRoot = Path.of(spec.bundlePath)
    val sourcesHash = hashSources(spec, bundleRoot)
    val resourcesHash = hashResources(spec, bundleRoot, outputDir)
    val metadataHash = hashMetadata(spec, bundleRoot)
    val classpathHash = hashClasspath(spec.classpath)
    return BundleCompileFingerprint(
      sourcesHash = sourcesHash,
      resourcesHash = resourcesHash,
      metadataHash = metadataHash,
      classpathHash = classpathHash,
      outputDirectory = outputDir.toAbsolutePath().normalize().pathString
    )
  }

  private fun hashSources(spec: CompileSpec, bundleRoot: Path): String {
    val md = MessageDigest.getInstance("SHA-256")
    updateStrings(md, spec.sourceRoots)
    val files = collectSourceFiles(spec)
    updateFiles(md, files, bundleRoot)
    return md.digest().toHex()
  }

  private fun collectSourceFiles(spec: CompileSpec): List<Path> =
    spec.sourceRoots.map { Path.of(it) }
      .filter { it.exists() && it.isDirectory() }
      .flatMap { root ->
        Files.walk(root).use { stream ->
          stream.filter { p -> p.isRegularFile() && p.toString().endsWith(".java") }.toList()
        }
      }

  private fun hashResources(spec: CompileSpec, bundleRoot: Path, outputDir: Path): String {
    val md = MessageDigest.getInstance("SHA-256")
    updateStrings(md, spec.resourceIncludes)
    updateStrings(md, spec.resourceExcludes)
    updateStrings(md, spec.sourceRoots)
    val files = collectResourceFiles(spec, bundleRoot, outputDir)
    updateFiles(md, files, bundleRoot)
    return md.digest().toHex()
  }

  private fun collectResourceFiles(spec: CompileSpec, bundleRoot: Path, outputDir: Path): List<Path> {
    val includes = if (spec.resourceIncludes.isEmpty()) listOf(".") else spec.resourceIncludes
    val includeMatchers = buildMatchers(bundleRoot, includes)
    val excludeMatchers = buildMatchers(bundleRoot, spec.resourceExcludes)
    val normalizedOut = outputDir.toAbsolutePath().normalize()

    val files = mutableListOf<Path>()
    if (bundleRoot.exists() && bundleRoot.isDirectory()) {
      Files.walk(bundleRoot).use { stream ->
        stream
          .filter { p -> p.isRegularFile() && !p.toString().endsWith(".java") }
          .filter { p -> !p.toAbsolutePath().normalize().startsWith(normalizedOut) }
          .forEach { file ->
            val rel = runCatching { file.relativeTo(bundleRoot) }.getOrNull() ?: return@forEach
            if (includeMatchers.none { it.matches(rel) }) return@forEach
            if (excludeMatchers.any { it.matches(rel) }) return@forEach
            files.add(file)
          }
      }
    }

    spec.sourceRoots.map { Path.of(it) }
      .filter { it.exists() && it.isDirectory() }
      .forEach { srcRoot ->
        Files.walk(srcRoot).use { stream ->
          stream
            .filter { p -> p.isRegularFile() && !p.toString().endsWith(".java") }
            .forEach { file ->
              val relToRoot = runCatching { file.relativeTo(bundleRoot) }.getOrNull()
              if (relToRoot != null && excludeMatchers.any { it.matches(relToRoot) }) return@forEach
              files.add(file)
            }
        }
      }

    spec.classpath.map { Path.of(it) }
      .filter { it.exists() && it.isRegularFile() }
      .filter { it.toAbsolutePath().normalize().startsWith(bundleRoot.toAbsolutePath().normalize()) }
      .forEach { files.add(it) }

    return files.distinct().sortedBy { it.absolutePathString() }
  }

  private fun hashMetadata(spec: CompileSpec, bundleRoot: Path): String {
    val md = MessageDigest.getInstance("SHA-256")
    updateStrings(md, listOf(spec.bsn, spec.version, spec.origin, spec.bundlePath))
    updateStrings(md, spec.resourceIncludes)
    updateStrings(md, spec.resourceExcludes)
    updateStrings(md, spec.sourceRoots)
    updateStrings(md, listOf(spec.executionEnvironment ?: "", spec.outputDirectory ?: ""))
    spec.compilerPrefs.toSortedMap().forEach { (k, v) ->
      updateString(md, k)
      updateString(md, v)
    }

    val settingsDir = bundleRoot.resolve(".settings")
    val metadataFiles = mutableListOf<Path>()
    bundleRoot.resolve("META-INF").resolve("MANIFEST.MF").takeIf { it.exists() }?.let(metadataFiles::add)
    bundleRoot.resolve("build.properties").takeIf { it.exists() }?.let(metadataFiles::add)
    if (settingsDir.exists() && settingsDir.isDirectory()) {
      Files.walk(settingsDir).use { stream ->
        stream.filter { it.isRegularFile() && it.name.endsWith(".prefs") }.forEach { metadataFiles.add(it) }
      }
    }
    updateFiles(md, metadataFiles.distinct().sortedBy { it.absolutePathString() }, bundleRoot)
    return md.digest().toHex()
  }

  private fun hashClasspath(classpath: List<String>): String {
    val md = MessageDigest.getInstance("SHA-256")
    classpath.forEach { entry ->
      updateString(md, entry)
      val path = runCatching { Path.of(entry) }.getOrNull() ?: return@forEach
      val stat = runCatching {
        val size = if (path.isRegularFile()) Files.size(path) else 0L
        val mtime = Files.getLastModifiedTime(path).toMillis()
        "$size:$mtime:${path.isDirectory()}"
      }.getOrNull() ?: "missing"
      updateString(md, stat)
    }
    return md.digest().toHex()
  }

  private fun updateFiles(md: MessageDigest, files: List<Path>, base: Path) {
    files.sortedBy { it.absolutePathString() }.forEach { file ->
      val rel = runCatching { file.relativeTo(base) }.getOrNull()?.pathString ?: file.absolutePathString()
      updateString(md, rel)
      Files.newInputStream(file).use { input ->
        val buf = ByteArray(8192)
        while (true) {
          val read = input.read(buf)
          if (read <= 0) break
          md.update(buf, 0, read)
        }
      }
    }
  }

  private fun updateStrings(md: MessageDigest, values: List<String>) {
    values.forEach { updateString(md, it) }
  }

  private fun updateString(md: MessageDigest, value: String) {
    md.update(value.toByteArray(StandardCharsets.UTF_8))
    md.update(0)
  }

  private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

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
