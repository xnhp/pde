package cn.varsa.pde.testflow

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class KnwfUnzipTest {
  @Rule @JvmField val tmp = TemporaryFolder()

  private fun zipOf(vararg entries: Pair<String, String>): ByteArray {
    val out = ByteArrayOutputStream()
    ZipOutputStream(out).use { zip ->
      for ((name, content) in entries) {
        zip.putNextEntry(ZipEntry(name))
        zip.write(content.toByteArray())
        zip.closeEntry()
      }
    }
    return out.toByteArray()
  }

  @Test
  fun `extracts entries preserving directory structure`() {
    val dest = tmp.root.toPath()
    val zip = zipOf(
      "Workflow/workflow.knime" to "root",
      "Workflow/Node (#1)/settings.xml" to "node",
    )

    unzipInto(ByteArrayInputStream(zip), dest)

    assertEquals("root", Files.readString(dest.resolve("Workflow/workflow.knime")))
    assertEquals("node", Files.readString(dest.resolve("Workflow/Node (#1)/settings.xml")))
  }

  @Test
  fun `rejects entries that escape the destination`() {
    val dest = tmp.root.toPath().resolve("inside")
    Files.createDirectories(dest)
    val zip = zipOf("../escape.txt" to "evil")

    val ex = assertFailsWith<IllegalStateException> {
      unzipInto(ByteArrayInputStream(zip), dest)
    }

    assertContains(ex.message!!, "escape.txt")
  }
}
