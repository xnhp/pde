package cn.varsa.pde.resolver.cli.config

import kotlin.io.path.createTempFile
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class WhitelistFileLoaderTest {
  @Test
  fun parsesEntries() {
    val file = createTempFile(prefix = "whitelist", suffix = ".txt")
    file.writeText(
      """
        org.eclipse.io
        # comment
        org.eclipse.swt  # inline

        org.knime.core
      """.trimIndent()
    )
    val parsed = WhitelistFileLoader.load(file)
    assertEquals(setOf("org.eclipse.io", "org.eclipse.swt", "org.knime.core"), parsed)
  }

  @Test
  fun returnsNullWhenMissing() {
    val file = createTempFile(prefix = "whitelist-missing", suffix = ".txt")
    val parsed = WhitelistFileLoader.load(file.resolveSibling(file.fileName.toString() + ".gone"))
    assertNull(parsed)
  }
}
