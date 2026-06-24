package cn.varsa.pde.testflow

import java.nio.file.Files
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TestflowResultsTest {
  @Rule @JvmField val tmp = TemporaryFolder()

  @Test
  fun `sums tests failures and errors across junit result files`() {
    val dir = tmp.root.toPath()
    Files.writeString(dir.resolve("TEST-a.xml"), """<testsuite name="a" tests="3" failures="1" errors="0"/>""")
    Files.writeString(dir.resolve("TEST-b.xml"), """<testsuite name="b" tests="2" failures="0" errors="1"/>""")

    val summary = summarizeTestflowResults(dir)

    assertEquals(5, summary.tests)
    assertEquals(1, summary.failures)
    assertEquals(1, summary.errors)
    assertFalse(summary.passed)
  }

  @Test
  fun `passed is true when there are tests and no failures or errors`() {
    val dir = tmp.root.toPath()
    Files.writeString(dir.resolve("TEST-ok.xml"), """<testsuite name="ok" tests="2" failures="0" errors="0"/>""")

    assertTrue(summarizeTestflowResults(dir).passed)
  }

  @Test
  fun `passed is false when no result files were written`() {
    assertFalse(summarizeTestflowResults(tmp.root.toPath()).passed)
  }

  @Test
  fun `finds result files written into nested subdirectories`() {
    // The runner writes TEST-*.xml into a tree mirroring the testflow's repository path.
    val dir = tmp.root.toPath()
    val nested = dir.resolve("Testflows (master)/knime-base")
    Files.createDirectories(nested)
    Files.writeString(
      nested.resolve("TEST-testExtractTableSpec.xml"),
      """<testsuite name="x" tests="22" failures="0" errors="0"/>""",
    )

    val summary = summarizeTestflowResults(dir)

    assertEquals(22, summary.tests)
    assertTrue(summary.passed)
  }
}
