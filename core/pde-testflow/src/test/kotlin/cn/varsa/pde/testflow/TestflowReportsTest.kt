package cn.varsa.pde.testflow

import java.nio.file.Files
import java.nio.file.Path
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TestflowReportsTest {
  @Rule @JvmField val tmp = TemporaryFolder()

  private fun writeSuite(file: Path, name: String, tests: Int, failures: Int, errors: Int) {
    Files.createDirectories(file.parent)
    Files.writeString(
      file,
      """<?xml version="1.0" encoding="UTF-8"?>
<testsuite name="$name" tests="$tests" failures="$failures" errors="$errors"><testcase name="c"/></testsuite>""",
    )
  }

  @Test
  fun `parseTestflowSuites returns one result per suite, recursively`() {
    val dir = tmp.root.toPath()
    writeSuite(dir.resolve("knime-base/TEST-a.xml"), "suiteA", 3, 1, 0)
    writeSuite(dir.resolve("knime-base/TEST-b.xml"), "suiteB", 2, 0, 0)

    val suites = parseTestflowSuites(dir).sortedBy { it.name }

    assertEquals(listOf("suiteA", "suiteB"), suites.map { it.name })
    assertEquals(1, suites[0].failures)
    assertTrue(suites[1].passed)
  }

  @Test
  fun `mergeJUnitXml wraps every testsuite in a testsuites element`() {
    val dir = tmp.root.toPath()
    writeSuite(dir.resolve("a/TEST-a.xml"), "suiteA", 1, 0, 0)
    writeSuite(dir.resolve("b/TEST-b.xml"), "suiteB", 1, 0, 0)

    val xml = mergeJUnitXml(dir)

    assertContains(xml, "<testsuites>")
    assertContains(xml, "name=\"suiteA\"")
    assertContains(xml, "name=\"suiteB\"")
    assertContains(xml, "</testsuites>")
    // the inner per-file <?xml?> declarations must be stripped (only one at the top)
    assertEquals(1, Regex("<\\?xml").findAll(xml).count())
  }

  @Test
  fun `teamCity messages mark a failing suite as failed`() {
    val dir = tmp.root.toPath()
    writeSuite(dir.resolve("TEST-a.xml"), "suiteA", 1, 1, 0)

    val text = teamCityTestflowMessages(dir).joinToString("\n")

    assertContains(text, "testStarted name='suiteA'")
    assertContains(text, "testFailed name='suiteA'")
    assertContains(text, "testFinished name='suiteA'")
  }

  @Test
  fun `teamCity messages do not fail a passing suite`() {
    val dir = tmp.root.toPath()
    writeSuite(dir.resolve("TEST-ok.xml"), "suiteOk", 2, 0, 0)

    val text = teamCityTestflowMessages(dir).joinToString("\n")

    assertContains(text, "testStarted name='suiteOk'")
    assertContains(text, "testFinished name='suiteOk'")
    assertTrue("testFailed" !in text)
  }
}
