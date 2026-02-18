import cn.varsa.pde.launch.JdtlsSmokeCommand
import org.junit.Test
import java.io.BufferedInputStream
import java.io.OutputStream
import java.net.URI
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.test.assertEquals

class JdtlsSmokeTest {
  @Test
  fun `smoke initialize with JDT LS`() {
    val root = envPath("JDTLS_ROOT") ?: createWorkspaceFixture()
    val dataDir = envPath("JDTLS_DATA") ?: root.resolve(".jdtls-data-test")
    val launcher = envPath("JDTLS_LAUNCHER")
    val config = envPath("JDTLS_CONFIG")
    val (resolvedLauncher, resolvedConfig) = if (launcher != null && config != null) {
      launcher to config
    } else {
      val home = ensureJdtlsCached()
      val configDir = home.resolve(selectConfigDir())
      val launcherJar = findLauncherJar(home)
      launcherJar to configDir
    }
    Files.createDirectories(dataDir)

    val exitCode = JdtlsSmokeCommand.main(
      arrayOf(
        "--launcher", resolvedLauncher.toString(),
        "--config", resolvedConfig.toString(),
        "--root", root.toString(),
        "--data", dataDir.toString(),
        "--timeout-ms", "60000"
      )
    )
    assertEquals(0, exitCode)
  }
}

private fun envPath(name: String): Path? {
  val value = System.getenv(name)?.trim().orEmpty()
  if (value.isBlank()) return null
  return Paths.get(value)
}

private fun ensureJdtlsCached(): Path {
  val cacheRoot = Paths.get(System.getProperty("user.home"), ".cache", "jdtls-smoke")
  val marker = cacheRoot.resolve("plugins")
  val configDir = cacheRoot.resolve(selectConfigDir())
  val launcher = findLauncherJarOrNull(cacheRoot)
  if (Files.isDirectory(marker) && Files.isDirectory(configDir) && launcher != null) return cacheRoot
  Files.createDirectories(cacheRoot)
  val archive = cacheRoot.resolve("jdt-language-server.tar.gz")
  if (!Files.exists(archive)) {
    download(
      "https://download.eclipse.org/jdtls/milestones/1.56.0/jdt-language-server-1.56.0-202601291528.tar.gz",
      archive
    )
  }
  extractTarGz(archive, cacheRoot)
  return cacheRoot
}

private fun download(url: String, target: Path) {
  URI(url).toURL().openStream().use { input ->
    Files.newOutputStream(target).use { output ->
      input.copyTo(output)
    }
  }
}

private fun findLauncherJar(home: Path): Path {
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

private fun findLauncherJarOrNull(home: Path): Path? {
  return try {
    findLauncherJar(home)
  } catch (_: Exception) {
    null
  }
}

private fun selectConfigDir(): String {
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

private fun createWorkspaceFixture(): Path {
  val workspace = Files.createTempDirectory("jdtls-workspace")
  Files.createDirectories(workspace.resolve(".metadata"))
  return workspace
}

private fun extractTarGz(archive: Path, targetDir: Path) {
  BufferedInputStream(Files.newInputStream(archive)).use { input ->
    val gzip = java.util.zip.GZIPInputStream(input)
    val header = ByteArray(512)
    while (true) {
      val read = readFully(gzip, header)
      if (read < header.size) return
      if (header.all { it == 0.toByte() }) return
      val name = header.copyOfRange(0, 100).toString(StandardCharsets.UTF_8).trim('\u0000')
      val sizeOctal = header.copyOfRange(124, 136).toString(StandardCharsets.UTF_8).trim('\u0000', ' ')
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
