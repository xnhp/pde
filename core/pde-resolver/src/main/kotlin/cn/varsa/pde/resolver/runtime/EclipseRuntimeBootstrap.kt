package cn.varsa.pde.resolver.runtime

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.net.URI
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.security.MessageDigest
import java.util.Properties
import java.util.zip.ZipInputStream
import kotlin.io.path.inputStream
import kotlin.io.path.outputStream

data class RuntimeManifest(
  val version: String,
  val requiredIUs: List<String>,
  val defaultRuntimeZip: String? = null,
  val defaultRuntimeZipSha256: String? = null,
)

object EclipseRuntimeBootstrap {
  private const val RUNTIME_MANIFEST_RESOURCE = "/runtime/eclipse-runtime-manifest.properties"
  private const val RUNTIME_KEY_VERSION = "1"
  private const val ARCHIVE_FILE = "runtime.zip"

  fun resolve(
    cacheDirOverride: Path? = null,
    p2Repositories: List<String> = emptyList(),
  ): Path {
    val manifest = loadManifest()
    val cacheRoot = (cacheDirOverride ?: defaultCacheDir()).toAbsolutePath().normalize()
    val key = cacheKey(manifest, p2Repositories)
    val runtimeRoot = cacheRoot.resolve(key)
    if (isReady(runtimeRoot)) {
      return runtimeRoot
    }
    Files.createDirectories(runtimeRoot)
    val archive = runtimeRoot.resolve(ARCHIVE_FILE)
    val source = runtimeZipUri(manifest)
    val checksum = runtimeZipSha256(manifest)
    if (!Files.exists(archive)) {
      download(source, archive)
    }
    verifySha256IfPresent(archive, checksum)
    extractZip(archive, runtimeRoot)
    require(isReady(runtimeRoot)) {
      "Invalid Eclipse runtime archive from $source: missing plugins directory"
    }
    return runtimeRoot
  }

  fun cacheKey(manifest: RuntimeManifest, p2Repositories: List<String>): String {
    val digest = MessageDigest.getInstance("SHA-256")
    digest.update(RUNTIME_KEY_VERSION.toByteArray(StandardCharsets.UTF_8))
    digest.update(0)
    digest.update(manifest.version.toByteArray(StandardCharsets.UTF_8))
    digest.update(0)
    manifest.requiredIUs.sorted().forEach {
      digest.update(it.toByteArray(StandardCharsets.UTF_8))
      digest.update(0)
    }
    p2Repositories
      .map(String::trim)
      .filter(String::isNotEmpty)
      .sorted()
      .forEach {
        digest.update(it.toByteArray(StandardCharsets.UTF_8))
        digest.update(0)
      }
    return digest.digest().toHex()
  }

  private fun defaultCacheDir(): Path {
    val base = System.getenv("XDG_CACHE_HOME")?.takeIf { it.isNotBlank() }?.let(Path::of)
      ?: Path.of(System.getProperty("user.home"), ".cache")
    return base.resolve("pde-runtime").resolve("eclipse")
  }

  private fun loadManifest(): RuntimeManifest {
    val stream = EclipseRuntimeBootstrap::class.java.getResourceAsStream(RUNTIME_MANIFEST_RESOURCE)
      ?: error("Missing runtime manifest resource: $RUNTIME_MANIFEST_RESOURCE")
    stream.use {
      val props = Properties()
      props.load(it)
      val version = props.getProperty("version")?.trim().orEmpty()
      require(version.isNotBlank()) { "Missing runtime manifest version" }
      val ius = props.getProperty("requiredIUs").orEmpty()
        .split(',')
        .map(String::trim)
        .filter(String::isNotBlank)
      require(ius.isNotEmpty()) { "Missing runtime manifest requiredIUs" }
      return RuntimeManifest(
        version = version,
        requiredIUs = ius,
        defaultRuntimeZip = props.getProperty("defaultRuntimeZip")?.trim(),
        defaultRuntimeZipSha256 = props.getProperty("defaultRuntimeZipSha256")?.trim(),
      )
    }
  }

  private fun runtimeZipUri(manifest: RuntimeManifest): URI {
    val runtimeZipLocation = System.getProperty("pde.eclipse.runtime.zip").orEmpty().trim()
      .ifBlank { System.getenv("PDE_ECLIPSE_RUNTIME_ZIP").orEmpty().trim() }
      .ifBlank { manifest.defaultRuntimeZip.orEmpty().trim() }
    require(runtimeZipLocation.isNotBlank()) {
      "No Eclipse runtime archive configured. Set pde.eclipse.runtime.zip or PDE_ECLIPSE_RUNTIME_ZIP."
    }
    return runCatching { URI.create(runtimeZipLocation) }.getOrElse { Paths.get(runtimeZipLocation).toUri() }
  }

  private fun runtimeZipSha256(manifest: RuntimeManifest): String? {
    return System.getProperty("pde.eclipse.runtime.zip.sha256").orEmpty().trim()
      .ifBlank { System.getenv("PDE_ECLIPSE_RUNTIME_ZIP_SHA256").orEmpty().trim() }
      .ifBlank { manifest.defaultRuntimeZipSha256.orEmpty().trim() }
      .ifBlank { null }
  }

  private fun download(source: URI, target: Path) {
    source.toURL().openStream().use { input ->
      Files.createDirectories(target.parent)
      target.outputStream().use { output ->
        input.copyTo(output)
      }
    }
  }

  private fun verifySha256IfPresent(archive: Path, expected: String?) {
    if (expected.isNullOrBlank()) {
      return
    }
    val actual = sha256(archive)
    require(actual.equals(expected, ignoreCase = true)) {
      "Eclipse runtime checksum mismatch for $archive: expected=$expected actual=$actual"
    }
  }

  private fun sha256(path: Path): String {
    val digest = MessageDigest.getInstance("SHA-256")
    path.inputStream().use { input ->
      val buffer = ByteArray(8192)
      while (true) {
        val read = input.read(buffer)
        if (read < 0) break
        digest.update(buffer, 0, read)
      }
    }
    return digest.digest().toHex()
  }

  private fun extractZip(archive: Path, destination: Path) {
    ZipInputStream(BufferedInputStream(archive.inputStream())).use { zip ->
      while (true) {
        val entry = zip.nextEntry ?: break
        val target = destination.resolve(entry.name).normalize()
        require(target.startsWith(destination)) { "Invalid zip entry: ${entry.name}" }
        if (entry.isDirectory) {
          Files.createDirectories(target)
        } else {
          Files.createDirectories(target.parent)
          BufferedOutputStream(target.outputStream()).use { out ->
            zip.copyTo(out)
          }
        }
      }
    }
  }

  private fun isReady(runtimeRoot: Path): Boolean = Files.isDirectory(runtimeRoot.resolve("plugins"))

  private fun ByteArray.toHex(): String = joinToString("") { b -> "%02x".format(b) }
}
