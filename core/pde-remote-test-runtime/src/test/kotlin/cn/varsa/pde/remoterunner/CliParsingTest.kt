package cn.varsa.pde.remoterunner

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
    assertEquals("build/results.xml", junit.path.toString())
  }

  @Test
  fun `parses forward log specs`() {
    val spec = parseForwardSpec("stdout=/tmp/stdout.pipe")
    assertEquals("stdout", spec.label)
    assertEquals("/tmp/stdout.pipe", spec.path.toString())
  }
}
