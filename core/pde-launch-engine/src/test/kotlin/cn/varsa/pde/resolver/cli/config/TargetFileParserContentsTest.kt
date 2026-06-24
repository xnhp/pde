package cn.varsa.pde.resolver.cli.config

import kotlin.io.path.createTempFile
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import java.net.URI

class TargetFileParserContentsTest {
  @Test
  fun parsesRepositoriesAndUnits() {
    val xml = """
      <target name="example" sequenceNumber="1">
        <locations>
          <location type="InstallableUnit">
            <repository location="https://example.com/updates"/>
            <repository location="file:/tmp/repo"/>
            <unit id="org.example.feature" version="1.2.3"/>
            <unit id="org.example.bundle"/>
          </location>
        </locations>
      </target>
    """.trimIndent()
    val path = createTempFile(prefix = "target", suffix = ".target")
    path.writeText(xml)

    val contents = TargetFileParser.parseContents(path)

    assertEquals(
      listOf(URI("https://example.com/updates"), URI("file:/tmp/repo")),
      contents.repositories
    )
    assertEquals(2, contents.installUnits.size)
    assertEquals(InstallUnitRef(id = "org.example.feature", version = "1.2.3"), contents.installUnits[0])
    assertEquals(InstallUnitRef(id = "org.example.bundle", version = null), contents.installUnits[1])
    assertTrue(contents.includeConfigurePhase)
  }

  @Test
  fun skipsInvalidRepositoryLocations() {
    val xml = """
      <target name="example" sequenceNumber="1">
        <locations>
          <location type="InstallableUnit">
            <repository location="https://example.com/updates"/>
            <repository location="::://bad"/>
          </location>
        </locations>
      </target>
    """.trimIndent()
    val path = createTempFile(prefix = "target", suffix = ".target")
    path.writeText(xml)

    val contents = TargetFileParser.parseContents(path)

    assertEquals(listOf(URI("https://example.com/updates")), contents.repositories)
    assertTrue(contents.installUnits.isEmpty())
    assertTrue(contents.includeConfigurePhase)
  }

  @Test
  fun parsesIncludeConfigurePhaseFromInstallableUnitLocations() {
    val xml = """
      <target name="example" sequenceNumber="1">
        <locations>
          <location type="Directory" includeConfigurePhase="false"/>
          <location type="InstallableUnit" includeConfigurePhase="false"/>
        </locations>
      </target>
    """.trimIndent()
    val path = createTempFile(prefix = "target", suffix = ".target")
    path.writeText(xml)

    val contents = TargetFileParser.parseContents(path)

    assertEquals(false, contents.includeConfigurePhase)
  }

  @Test
  fun defaultsIncludeConfigurePhaseToTrueWhenMissing() {
    val xml = """
      <target name="example" sequenceNumber="1">
        <locations>
          <location type="InstallableUnit"/>
        </locations>
      </target>
    """.trimIndent()
    val path = createTempFile(prefix = "target", suffix = ".target")
    path.writeText(xml)

    val contents = TargetFileParser.parseContents(path)

    assertTrue(contents.includeConfigurePhase)
  }
}
