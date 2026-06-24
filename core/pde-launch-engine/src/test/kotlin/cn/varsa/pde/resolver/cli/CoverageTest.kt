package cn.varsa.pde.resolver.cli

import java.nio.file.Files
import java.nio.file.Path
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CoverageTest {
  @Rule @JvmField val tmp = TemporaryFolder()

  @Test
  fun `builds the jacoco agent vmarg with destfile`() {
    val arg = jacocoAgentVmArg(Path.of("/t/jacocoagent.jar"), Path.of("/cov/test-1.exec"))

    assertEquals("-javaagent:/t/jacocoagent.jar=destfile=/cov/test-1.exec", arg)
  }

  @Test
  fun `exec file path uses kind and id under the dir`() {
    assertEquals(Path.of("/cov/testflow-abc.exec"), jacocoExecFile(Path.of("/cov"), "testflow", "abc"))
  }

  @Test
  fun `resolveCoverageDir makes a relative dir absolute and normalized`() {
    val resolved = resolveCoverageDir("cov")

    assertTrue(resolved.isAbsolute, "coverage dir must be absolute for the forked-JVM agent destfile")
    assertEquals(Path.of("cov").toAbsolutePath().normalize(), resolved)
  }

  @Test
  fun `coverageVmArgOrNull emits an absolute destfile for a relative coverage dir`() {
    val agent = tmp.newFile("org.jacoco.agent.jar").toPath()
    val relDir = "pde-cov-rel-test"
    try {
      val arg = coverageVmArgOrNull(relDir, agent.toString(), "test")!!

      val destfile = Path.of(arg.substringAfter("destfile="))
      assertTrue(destfile.isAbsolute, "destfile must be absolute (a relative one is lost by the forked JVM): $destfile")
      assertTrue(destfile.fileName.toString().endsWith(".exec"))
    } finally {
      Files.deleteIfExists(Path.of(relDir))
    }
  }

  @Test
  fun `jacocoAgentVmArgFor builds a fresh-exec destfile arg under the dir`() {
    val arg = jacocoAgentVmArgFor(Path.of("/t/jacocoagent.jar"), Path.of("/cov"), "test")

    assertTrue(arg.startsWith("-javaagent:/t/jacocoagent.jar=destfile=/cov/test-"))
    assertTrue(arg.endsWith(".exec"))
  }
}
