package cn.varsa.pde.resolver.cli.config

import kotlin.io.path.createTempFile
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class TargetDefinitionStartupParserTest {
  @Test
  fun parsesBundleLevels() {
    val xml = """
      <project version="4">
        <component name="TcRacTargetDefinitions">
          <startupLevels>
            <bundleLevel bundleSymbolicName="org.eclipse.osgi" startupLevel="-1" />
            <bundleLevel bundleSymbolicName="org.eclipse.equinox.common" startupLevel="2" />
          </startupLevels>
        </component>
      </project>
    """.trimIndent()
    val path = createTempFile(prefix = "startup", suffix = ".xml")
    path.writeText(xml)

    val parsed = TargetDefinitionStartupParser.parse(path)
    assertEquals(mapOf("org.eclipse.osgi" to -1, "org.eclipse.equinox.common" to 2), parsed)
  }

  @Test
  fun returnsNullWhenComponentMissing() {
    val xml = """<project version="4"></project>"""
    val path = createTempFile(prefix = "startup-missing", suffix = ".xml")
    path.writeText(xml)
    val parsed = TargetDefinitionStartupParser.parse(path)
    assertNull(parsed)
  }
}
