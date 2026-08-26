package cn.varsa.pde.resolver.cli

import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.FileTime
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class EquinoxRuntimeExtractionTest {
  @Rule @JvmField val tmp = TemporaryFolder()

  @Test
  fun `second call with unchanged archive reuses extraction and keeps configuration`() {
    val archive = writeArchive("runtime.zip", "plugins/a.jar" to "A")
    val runtimeRoot = tmp.root.toPath().resolve("runtime")

    assertTrue(ensureExtractedEquinoxAppRuntime(archive, runtimeRoot))
    assertTrue(Files.isRegularFile(runtimeRoot.resolve(EQUINOX_RUNTIME_STAMP_FILE)))
    val marker = runtimeRoot.resolve("configuration").resolve("marker")
    Files.createDirectories(marker.parent)
    Files.writeString(marker, "keep")

    assertFalse(ensureExtractedEquinoxAppRuntime(archive, runtimeRoot))
    assertEquals("keep", Files.readString(marker))
    assertEquals("A", Files.readString(runtimeRoot.resolve("plugins/a.jar")))
  }

  @Test
  fun `changed archive re-extracts and drops previous contents`() {
    val archive = writeArchive("runtime.zip", "plugins/a.jar" to "A")
    val runtimeRoot = tmp.root.toPath().resolve("runtime")
    ensureExtractedEquinoxAppRuntime(archive, runtimeRoot)
    val marker = runtimeRoot.resolve("configuration").resolve("marker")
    Files.createDirectories(marker.parent)
    Files.writeString(marker, "stale")

    // Same path, different content and a mtime guaranteed to differ.
    writeArchive("runtime.zip", "plugins/b.jar" to "B")
    Files.setLastModifiedTime(archive, FileTime.fromMillis(Files.getLastModifiedTime(archive).toMillis() + 10_000))

    assertTrue(ensureExtractedEquinoxAppRuntime(archive, runtimeRoot))
    assertFalse(Files.exists(marker))
    assertFalse(Files.exists(runtimeRoot.resolve("plugins/a.jar")))
    assertEquals("B", Files.readString(runtimeRoot.resolve("plugins/b.jar")))
  }

  @Test
  fun `force re-extracts an up-to-date runtime`() {
    val archive = writeArchive("runtime.zip", "plugins/a.jar" to "A")
    val runtimeRoot = tmp.root.toPath().resolve("runtime")
    ensureExtractedEquinoxAppRuntime(archive, runtimeRoot)
    val marker = runtimeRoot.resolve("configuration").resolve("marker")
    Files.createDirectories(marker.parent)
    Files.writeString(marker, "stale")

    assertTrue(ensureExtractedEquinoxAppRuntime(archive, runtimeRoot, force = true))
    assertFalse(Files.exists(marker))
  }

  private fun writeArchive(name: String, vararg entries: Pair<String, String>): Path {
    val archive = tmp.root.toPath().resolve(name)
    ZipOutputStream(Files.newOutputStream(archive)).use { zip ->
      entries.forEach { (entryName, content) ->
        zip.putNextEntry(ZipEntry(entryName))
        zip.write(content.toByteArray())
        zip.closeEntry()
      }
    }
    return archive
  }
}
