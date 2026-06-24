package cn.varsa.pde.coverage

import cn.varsa.pde.cli.support.findJarInDir
import java.nio.file.Files
import java.nio.file.Path
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CoverageTest {
  @Rule @JvmField val tmp = TemporaryFolder()

  @Test
  fun `findJarInDir finds a jar by name prefix`() {
    val lib = tmp.root.toPath()
    Files.writeString(lib.resolve("org.jacoco.agent-0.8.12-runtime.jar"), "")
    Files.writeString(lib.resolve("org.jacoco.cli-0.8.12-nodeps.jar"), "")
    Files.writeString(lib.resolve("pde-launch-engine-0.0.7.jar"), "")

    assertEquals(lib.resolve("org.jacoco.agent-0.8.12-runtime.jar"), findJarInDir(lib, "org.jacoco.agent"))
    assertEquals(lib.resolve("org.jacoco.cli-0.8.12-nodeps.jar"), findJarInDir(lib, "org.jacoco.cli"))
  }

  @Test
  fun `findJarInDir returns null when no jar matches`() {
    val result = findJarInDir(tmp.root.toPath(), "org.jacoco.agent")

    assertNull(result)
  }

  @Test
  fun `findExecFiles collects exec files recursively`() {
    val dir = tmp.root.toPath()
    Files.createDirectories(dir.resolve("a"))
    Files.writeString(dir.resolve("test-1.exec"), "")
    Files.writeString(dir.resolve("a/testflow-2.exec"), "")
    Files.writeString(dir.resolve("notes.txt"), "")

    val found = findExecFiles(dir).map { it.fileName.toString() }

    assertEquals(listOf("test-1.exec", "testflow-2.exec"), found.sorted())
  }

  @Test
  fun `jacocoReportArgs assembles the jacococli report invocation`() {
    val args = jacocoReportArgs(
      execFiles = listOf(Path.of("/c/a.exec"), Path.of("/c/b.exec")),
      classDirs = listOf(Path.of("/b/bin")),
      sourceDirs = listOf(Path.of("/b/src")),
      xmlOut = Path.of("/out/jacoco.xml"),
      htmlOut = Path.of("/out/html"),
    )

    assertEquals(
      listOf(
        "report", "/c/a.exec", "/c/b.exec",
        "--classfiles", "/b/bin", "--sourcefiles", "/b/src",
        "--xml", "/out/jacoco.xml", "--html", "/out/html",
      ),
      args,
    )
  }

  @Test
  fun `jacocoReportArgs omits html when not requested`() {
    val args = jacocoReportArgs(
      execFiles = listOf(Path.of("/c/a.exec")),
      classDirs = listOf(Path.of("/b/bin")),
      sourceDirs = emptyList(),
      xmlOut = Path.of("/out/jacoco.xml"),
      htmlOut = null,
    )

    assertEquals(listOf("report", "/c/a.exec", "--classfiles", "/b/bin", "--xml", "/out/jacoco.xml"), args)
  }

  @Test
  fun `coverageClassfileInputs uses the dir as-is when no jar anywhere in the tree`() {
    val dir = tmp.root.toPath().resolve("bin")
    Files.createDirectories(dir.resolve("org").resolve("knime"))

    assertEquals(listOf(dir), coverageClassfileInputs(dir))
  }

  @Test
  fun `coverageClassfileInputs expands to package roots and drops a nested bundle jar`() {
    val dir = tmp.root.toPath().resolve("bin")
    Files.createDirectories(dir.resolve("org").resolve("knime"))
    Files.createDirectories(dir.resolve("META-INF"))
    Files.writeString(dir.resolve("knime-base.jar"), "")

    assertEquals(listOf(dir.resolve("META-INF"), dir.resolve("org")), coverageClassfileInputs(dir))
  }

  @Test
  fun `coverageClassfileInputs drops a subdir with a jar anywhere beneath (Maven bin target)`() {
    val dir = tmp.root.toPath().resolve("bin")
    Files.createDirectories(dir.resolve("org").resolve("knime"))
    Files.createDirectories(dir.resolve("target"))
    Files.writeString(dir.resolve("target").resolve("org.knime.ext.foo-5.12.0-SNAPSHOT.jar"), "")

    // a jar lives under bin/target -> expand to jar-free immediate subdirs only, dropping target/
    assertEquals(listOf(dir.resolve("org")), coverageClassfileInputs(dir))
  }

  /**
   * Roundtrip over the real bundled jacococli on a checked-in fixture (a class + its `.exec`):
   * proves `pde coverage report`'s invocation produces a structurally-valid, Sonar-ingestible
   * `jacoco.xml`. Always-on (cli is on the test runtime classpath); no KNIME/target needed.
   */
  @Test
  fun `coverage report roundtrip produces a Sonar-ingestible jacoco xml`() {
    val execUrl = javaClass.getResource("/coverage/sample.exec") ?: error("missing fixture /coverage/sample.exec")
    val coverageDir = Path.of(execUrl.toURI()).parent
    val classesDir = coverageDir.resolve("classes")
    val cli = System.getProperty("java.class.path").split(System.getProperty("path.separator"))
      .map { Path.of(it) }
      .firstOrNull { it.fileName?.toString()?.startsWith("org.jacoco.cli") == true }
      ?: error("org.jacoco.cli jar not on the test runtime classpath")
    val javaBin = Path.of(System.getProperty("java.home"), "bin", "java").toString()
    val out = tmp.root.toPath().resolve("jacoco.xml")

    val exit = runJacocoReport(
      cli, javaBin,
      jacocoReportArgs(findExecFiles(coverageDir), listOf(classesDir), emptyList(), out, null),
    )

    assertEquals(0, exit, "jacococli report should succeed")
    val xml = Files.readString(out)
    assertTrue(xml.contains("<report"), "expected a <report> root (jacoco_coverage.py / sonar.coverage.jacoco.xmlReportPaths shape)")
    assertTrue(xml.contains("name=\"Sample\""), "expected the fixture class in the report")
    val lineCovered = Regex("""<counter type="LINE" missed="\d+" covered="(\d+)"/>""")
      .findAll(xml).map { it.groupValues[1].toInt() }.maxOrNull() ?: 0
    assertTrue(lineCovered > 0, "expected non-zero LINE coverage from the fixture run")
  }
}
