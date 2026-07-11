package cn.varsa.pde.launch

import java.io.BufferedInputStream
import java.io.OutputStream
import java.net.URI
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

data class JdtlsArtifact(val url: String, val label: String, val fileName: String)

/**
 * Resolves, downloads, and caches a JDT LS distribution, and locates its
 * launcher jar/config dir. Shared by `pde lsp run` and the JDT LS smoke tests.
 */
object JdtlsRuntime {
  fun resolveArtifact(): JdtlsArtifact {
    val urlOverride = System.getProperty("jdtls.url")?.trim().orEmpty()
      .ifBlank { System.getenv("JDTLS_URL")?.trim().orEmpty() }
    if (urlOverride.isNotBlank()) {
      val fileName = urlOverride.substringAfterLast('/')
      val label = fileName.removeSuffix(".tar.gz")
      return JdtlsArtifact(urlOverride, label, fileName)
    }
    val version = System.getProperty("jdtls.version")?.trim().orEmpty()
      .ifBlank { System.getenv("JDTLS_VERSION")?.trim().orEmpty() }
      .ifBlank { "1.56.0" }
    val build = System.getProperty("jdtls.build")?.trim().orEmpty()
      .ifBlank { System.getenv("JDTLS_BUILD")?.trim().orEmpty() }
      .ifBlank { "202601291528" }
    val fileName = "jdt-language-server-${version}-${build}.tar.gz"
    val url = "https://download.eclipse.org/jdtls/milestones/${version}/${fileName}"
    val label = "jdtls-${version}-${build}"
    return JdtlsArtifact(url, label, fileName)
  }

  fun cacheRoot(artifact: JdtlsArtifact = resolveArtifact()): Path =
    Paths.get(System.getProperty("user.home"), ".cache", "pde", "jdtls", artifact.label)

  /** Returns the cache dir if a complete JDT LS install is already cached, else null. Never downloads. */
  fun findCached(artifact: JdtlsArtifact = resolveArtifact()): Path? {
    val root = cacheRoot(artifact)
    val marker = root.resolve("plugins")
    val configDir = root.resolve(selectConfigDir())
    val launcher = findLauncherJarOrNull(root)
    return if (Files.isDirectory(marker) && Files.isDirectory(configDir) && launcher != null) root else null
  }

  fun ensureCached(artifact: JdtlsArtifact = resolveArtifact()): Path {
    findCached(artifact)?.let { return it }
    val cacheRoot = cacheRoot(artifact)
    Files.createDirectories(cacheRoot)
    val archive = cacheRoot.resolve(artifact.fileName)
    if (!Files.exists(archive)) {
      download(artifact.url, archive)
    }
    extractTarGz(archive, cacheRoot)
    return cacheRoot
  }

  fun findLauncherJar(home: Path): Path {
    val plugins = home.resolve("plugins")
    val candidates = Files.list(plugins).use { stream ->
      stream.filter { path ->
        val name = path.fileName.toString()
        name.startsWith("org.eclipse.equinox.launcher") && name.endsWith(".jar")
      }.toList()
    }
    if (candidates.isEmpty()) {
      throw IllegalStateException("No launcher jar found in ${plugins}")
    }
    val generic = candidates.firstOrNull { path ->
      val name = path.fileName.toString()
      name.startsWith("org.eclipse.equinox.launcher_") &&
        !name.contains("win32") && !name.contains("gtk.linux") && !name.contains("macosx")
    }
    if (generic != null) return generic

    val arch = System.getProperty("os.arch").lowercase()
    val os = System.getProperty("os.name").lowercase()
    val preferred = candidates.firstOrNull { path ->
      val name = path.fileName.toString().lowercase()
      when {
        os.contains("win") -> name.contains("win32")
        os.contains("mac") -> name.contains("macosx") && (arch.contains("aarch64") || arch.contains("arm64") == name.contains("aarch64"))
        else -> name.contains("gtk.linux") && (arch.contains("aarch64") || arch.contains("arm64") == name.contains("aarch64"))
      }
    }
    return preferred ?: candidates.first()
  }

  fun findLauncherJarOrNull(home: Path): Path? {
    return try {
      findLauncherJar(home)
    } catch (_: Exception) {
      null
    }
  }

  fun selectConfigDir(): String {
    val os = System.getProperty("os.name").lowercase()
    val arch = System.getProperty("os.arch").lowercase()
    return when {
      os.contains("win") -> "config_win"
      os.contains("mac") && (arch.contains("aarch64") || arch.contains("arm64")) -> "config_mac_arm"
      os.contains("mac") -> "config_mac"
      arch.contains("aarch64") || arch.contains("arm64") -> "config_linux_arm"
      else -> "config_linux"
    }
  }

  private fun download(url: String, target: Path) {
    URI(url).toURL().openStream().use { input ->
      Files.newOutputStream(target).use { output ->
        input.copyTo(output)
      }
    }
  }

  private val nul = 0.toChar()

  private fun extractTarGz(archive: Path, targetDir: Path) {
    BufferedInputStream(Files.newInputStream(archive)).use { input ->
      val gzip = java.util.zip.GZIPInputStream(input)
      val header = ByteArray(512)
      while (true) {
        val read = readFully(gzip, header)
        if (read < header.size) return
        if (header.all { it == 0.toByte() }) return
        val name = header.copyOfRange(0, 100).toString(StandardCharsets.UTF_8).trim(nul)
        val sizeOctal = header.copyOfRange(124, 136).toString(StandardCharsets.UTF_8).trim(nul, ' ')
        val size = sizeOctal.toLongOrNull(8) ?: 0
        val typeFlag = header[156].toInt().toChar()
        val entryPath = targetDir.resolve(name).normalize()
        when (typeFlag) {
          '5' -> Files.createDirectories(entryPath)
          else -> {
            entryPath.parent?.let { Files.createDirectories(it) }
            Files.newOutputStream(entryPath).use { output ->
              copyFixed(gzip, output, size)
            }
          }
        }
        val remainder = (512 - (size % 512)) % 512
        if (remainder > 0) {
          skipFully(gzip, remainder)
        }
      }
    }
  }

  private fun copyFixed(input: java.util.zip.GZIPInputStream, output: OutputStream, size: Long) {
    var remaining = size
    val buffer = ByteArray(8192)
    while (remaining > 0) {
      val read = input.read(buffer, 0, buffer.size.coerceAtMost(remaining.toInt()))
      if (read < 0) break
      output.write(buffer, 0, read)
      remaining -= read
    }
  }

  private fun readFully(input: java.util.zip.GZIPInputStream, buffer: ByteArray): Int {
    var offset = 0
    while (offset < buffer.size) {
      val read = input.read(buffer, offset, buffer.size - offset)
      if (read < 0) return offset
      offset += read
    }
    return offset
  }

  private fun skipFully(input: java.util.zip.GZIPInputStream, size: Long) {
    var remaining = size
    while (remaining > 0) {
      val skipped = input.skip(remaining)
      if (skipped <= 0) {
        if (input.read() == -1) return
        remaining -= 1
      } else {
        remaining -= skipped
      }
    }
  }
}
