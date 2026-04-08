package cn.varsa.pde.resolver.cli

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import java.net.URI
import java.nio.file.Files
import java.nio.file.Paths

class MirrorHelpersTest {
  @Test
  fun resolveMirrorDestinationUsesUri() {
    val baseDir = Files.createTempDirectory("mirror-dest")
    val uri = resolveMirrorDestination(baseDir, "file:/tmp/mirror")
    assertEquals(URI("file:/tmp/mirror"), uri)
  }

  @Test
  fun resolveMirrorDestinationResolvesRelativePath() {
    val baseDir = Files.createTempDirectory("mirror-dest")
    val uri = resolveMirrorDestination(baseDir, "local-mirror")
    assertEquals(baseDir.resolve("local-mirror").toUri(), uri)
  }

  @Test
  fun resolveMirrorWriteModeAcceptsClean() {
    assertEquals("clean", resolveMirrorWriteMode("clean"))
    assertEquals("clean", resolveMirrorWriteMode(" CLEAN "))
  }

  @Test
  fun resolveMirrorWriteModeRejectsUnknown() {
    assertNull(resolveMirrorWriteMode("append"))
  }

  @Test
  fun deriveMirrorLogFileHandlesExtensions() {
    val base = Paths.get("mirror.log")
    val derived = deriveMirrorLogFile(base, "metadata-1")
    assertEquals(Paths.get("mirror-metadata-1.log"), derived)
  }

  @Test
  fun deriveMirrorLogFileHandlesMissingExtension() {
    val base = Paths.get("mirror")
    val derived = deriveMirrorLogFile(base, "metadata-1")
    assertEquals(Paths.get("mirror-metadata-1"), derived)
  }
}
