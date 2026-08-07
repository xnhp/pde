package cn.varsa.pde.remoterunner

import java.nio.file.Paths
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CliParsingTest {
  @Test
  fun `parses port range`() {
    assertEquals(5000..5005, parsePortRange("5000-5005"))
  }

  @Test
  fun `parses report specs`() {
    assertTrue(parseReportTarget("teamcity") is ReportTarget.TeamCity)
    val junit = parseReportTarget("junit-xml:build/results.xml") as ReportTarget.JUnitXml
    // Compare Path to Path: Path.toString() uses the platform separator, so a string
    // comparison would fail on Windows (`build\results.xml`).
    assertEquals(Paths.get("build/results.xml"), junit.path)
  }

  @Test
  fun `parses forward log specs`() {
    val spec = parseForwardSpec("stdout=/tmp/stdout.pipe")
    assertEquals("stdout", spec.label)
    assertEquals(Paths.get("/tmp/stdout.pipe"), spec.path)
  }
}
