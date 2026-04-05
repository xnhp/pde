package cn.varsa.pde.resolver.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class EclipseRuntimeBootstrapTest {
  @Test
  fun cacheKeyChangesWhenManifestChanges() {
    val base = RuntimeManifest(
      version = "1.0.0",
      requiredIUs = listOf("a", "b"),
      defaultRuntimeZip = null,
      defaultRuntimeZipSha256 = null,
    )
    val changed = base.copy(version = "1.0.1")

    val keyA = EclipseRuntimeBootstrap.cacheKey(base, emptyList())
    val keyB = EclipseRuntimeBootstrap.cacheKey(changed, emptyList())

    assertNotEquals(keyA, keyB)
  }

  @Test
  fun cacheKeyIgnoresRepositoryOrder() {
    val manifest = RuntimeManifest(
      version = "1.0.0",
      requiredIUs = listOf("a", "b"),
      defaultRuntimeZip = null,
      defaultRuntimeZipSha256 = null,
    )

    val keyA = EclipseRuntimeBootstrap.cacheKey(manifest, listOf("https://a", "https://b"))
    val keyB = EclipseRuntimeBootstrap.cacheKey(manifest, listOf("https://b", "https://a"))

    assertEquals(keyA, keyB)
  }

  @Test
  fun resolveExtractsAndReusesCachedRuntime() {
    val tempDir = Files.createTempDirectory("runtime-bootstrap-test")
    val runtimeZip = tempDir.resolve("runtime.zip")
    writeRuntimeZip(runtimeZip)
    val cacheRoot = tempDir.resolve("cache")

    val previousZip = System.getProperty("pde.eclipse.runtime.zip")
    val previousSha = System.getProperty("pde.eclipse.runtime.zip.sha256")
    try {
      System.setProperty("pde.eclipse.runtime.zip", runtimeZip.toUri().toString())
      System.clearProperty("pde.eclipse.runtime.zip.sha256")

      val first = EclipseRuntimeBootstrap.resolve(cacheRoot, emptyList())
      assertTrue(Files.isDirectory(first.resolve("plugins")))

      Files.delete(runtimeZip)
      val second = EclipseRuntimeBootstrap.resolve(cacheRoot, emptyList())
      assertEquals(first, second)
      assertTrue(Files.isDirectory(second.resolve("plugins")))
    } finally {
      restoreSystemProperty("pde.eclipse.runtime.zip", previousZip)
      restoreSystemProperty("pde.eclipse.runtime.zip.sha256", previousSha)
      try {
        tempDir.toFile().deleteRecursively()
      } catch (_: Exception) {
        // best-effort cleanup
      }
    }
  }

  @Test
  fun resolveSupportsFilesystemPathSetting() {
    val tempDir = Files.createTempDirectory("runtime-bootstrap-path-test")
    val runtimeZip = tempDir.resolve("runtime.zip")
    writeRuntimeZip(runtimeZip)
    val cacheRoot = tempDir.resolve("cache")

    val previousZip = System.getProperty("pde.eclipse.runtime.zip")
    val previousSha = System.getProperty("pde.eclipse.runtime.zip.sha256")
    try {
      System.setProperty("pde.eclipse.runtime.zip", runtimeZip.toAbsolutePath().toString())
      System.clearProperty("pde.eclipse.runtime.zip.sha256")

      val resolved = EclipseRuntimeBootstrap.resolve(cacheRoot, emptyList())
      assertTrue(Files.isDirectory(resolved.resolve("plugins")))
    } finally {
      restoreSystemProperty("pde.eclipse.runtime.zip", previousZip)
      restoreSystemProperty("pde.eclipse.runtime.zip.sha256", previousSha)
      try {
        tempDir.toFile().deleteRecursively()
      } catch (_: Exception) {
        // best-effort cleanup
      }
    }
  }

  @Test
  fun resolveFailsOnChecksumMismatch() {
    val tempDir = Files.createTempDirectory("runtime-bootstrap-sha-test")
    val runtimeZip = tempDir.resolve("runtime.zip")
    writeRuntimeZip(runtimeZip)
    val cacheRoot = tempDir.resolve("cache")

    val previousZip = System.getProperty("pde.eclipse.runtime.zip")
    val previousSha = System.getProperty("pde.eclipse.runtime.zip.sha256")
    try {
      System.setProperty("pde.eclipse.runtime.zip", runtimeZip.toUri().toString())
      System.setProperty("pde.eclipse.runtime.zip.sha256", "deadbeef")

      try {
        EclipseRuntimeBootstrap.resolve(cacheRoot, emptyList())
        fail("Expected checksum mismatch")
      } catch (expected: IllegalArgumentException) {
        assertTrue(expected.message?.contains("checksum mismatch") == true)
      }
    } finally {
      restoreSystemProperty("pde.eclipse.runtime.zip", previousZip)
      restoreSystemProperty("pde.eclipse.runtime.zip.sha256", previousSha)
      try {
        tempDir.toFile().deleteRecursively()
      } catch (_: Exception) {
        // best-effort cleanup
      }
    }
  }

  private fun writeRuntimeZip(path: Path) {
    ZipOutputStream(Files.newOutputStream(path)).use { zip ->
      zip.putNextEntry(ZipEntry("plugins/"))
      zip.closeEntry()
      zip.putNextEntry(ZipEntry("plugins/org.eclipse.equinox.launcher_1.0.0.jar"))
      zip.write(byteArrayOf(0))
      zip.closeEntry()
    }
  }

  private fun restoreSystemProperty(name: String, value: String?) {
    if (value == null) {
      System.clearProperty(name)
    } else {
      System.setProperty(name, value)
    }
  }
}
