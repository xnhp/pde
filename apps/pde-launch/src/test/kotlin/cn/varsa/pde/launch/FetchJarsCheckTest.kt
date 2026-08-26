package cn.varsa.pde.launch

import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path
import java.util.logging.Handler
import java.util.logging.Level
import java.util.logging.LogRecord
import java.util.logging.Logger
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** A pom.xml whose maven-dependency-plugin copies dependencies to [outputDirectory] (default dir when null). */
internal fun dependencyPluginPom(outputDirectory: String? = null, goal: String = "copy-dependencies"): String {
  val configuration = outputDirectory?.let { "<configuration><outputDirectory>$it</outputDirectory></configuration>" }.orEmpty()
  return """
    <project xmlns="http://maven.apache.org/POM/4.0.0">
      <modelVersion>4.0.0</modelVersion>
      <groupId>org.example</groupId>
      <artifactId>fetch-jars</artifactId>
      <version>1</version>
      <build>
        <plugins>
          <plugin>
            <groupId>org.apache.maven.plugins</groupId>
            <artifactId>maven-dependency-plugin</artifactId>
            <executions>
              <execution>
                <phase>package</phase>
                <goals><goal>$goal</goal></goals>
                $configuration
              </execution>
            </executions>
          </plugin>
        </plugins>
      </build>
    </project>
  """.trimIndent()
}

private const val POM_WITHOUT_DEPENDENCY_PLUGIN = """
  <project xmlns="http://maven.apache.org/POM/4.0.0">
    <modelVersion>4.0.0</modelVersion>
    <groupId>org.example</groupId><artifactId>other</artifactId><version>1</version>
    <build><plugins><plugin><artifactId>maven-jar-plugin</artifactId></plugin></plugins></build>
  </project>
"""

class FetchJarsCheckTest {
  private fun fetchDir(bundleDir: Path, relative: String, pom: String = dependencyPluginPom()): Path {
    val dir = Files.createDirectories(bundleDir.resolve(relative))
    Files.writeString(dir.resolve("pom.xml"), pom)
    return dir
  }

  private fun captureWarnings(block: (Logger) -> Unit): List<String> {
    val logger = Logger.getLogger("FetchJarsCheckTest-${System.nanoTime()}")
    logger.useParentHandlers = false
    val messages = mutableListOf<String>()
    logger.addHandler(object : Handler() {
      override fun publish(record: LogRecord) { if (record.level == Level.WARNING) messages.add(record.message) }
      override fun flush() {}
      override fun close() {}
    })
    block(logger)
    return messages
  }

  @Test
  fun `discovers fetch_jars with default output dir`() {
    val bundleDir = Files.createTempDirectory("fetch-jars-default")
    val dir = fetchDir(bundleDir, "libs/fetch_jars")

    val projects = discoverFetchJarsProjects(bundleDir)

    assertEquals(listOf(FetchJarsProject(dir, listOf(dir.resolve("target/dependency")))), projects)
    assertEquals(listOf(dir), discoverRunnableFetchJarsDirs(bundleDir))
  }

  @Test
  fun `discovers versioned and dash-named helper dirs`() {
    val bundleDir = Files.createTempDirectory("fetch-jars-versioned")
    val v422 = fetchDir(bundleDir, "lib/postgresql/fetch_v422_jars")
    val dashed = fetchDir(bundleDir, "lib/other/fetch-jars", dependencyPluginPom(goal = "copy"))

    assertEquals(setOf(v422, dashed), discoverFetchJarsProjects(bundleDir).map { it.dir }.toSet())
  }

  @Test
  fun `ignores fetch_jars dir whose pom lacks the dependency plugin or has no pom`() {
    val bundleDir = Files.createTempDirectory("fetch-jars-nopom")
    fetchDir(bundleDir, "lib/fetch_jars", POM_WITHOUT_DEPENDENCY_PLUGIN)
    Files.createDirectories(bundleDir.resolve("other/fetch_jars"))

    assertTrue(discoverFetchJarsProjects(bundleDir).isEmpty())
    assertTrue(findEmptyFetchJarsOutputDirs(bundleDir).isEmpty())
  }

  @Test
  fun `resolves relative outputDirectory against the fetch dir`() {
    val bundleDir = Files.createTempDirectory("fetch-jars-outdir")
    val dir = fetchDir(bundleDir, "lib/h2/fetch_v14_jars", dependencyPluginPom("../1.4.196/jdbc"))

    val project = discoverFetchJarsProjects(bundleDir).single()

    assertEquals(listOf(bundleDir.resolve("lib/h2/1.4.196/jdbc")), project.outputDirs)
    assertEquals(dir, project.dir)
  }

  @Test
  fun `flags missing or empty output dirs and names both dirs in the warning`() {
    val bundleDir = Files.createTempDirectory("fetch-jars-empty")
    val dir = fetchDir(bundleDir, "lib/fetch_jars", dependencyPluginPom("\${project.basedir}/.."))

    assertEquals(listOf(bundleDir.resolve("lib") to dir), findEmptyFetchJarsOutputDirs(bundleDir))
    val warnings = captureWarnings { warnOnEmptyFetchJarsLibs(bundleDir, it) }
    assertTrue(warnings.any { it.contains(bundleDir.resolve("lib").toString()) && it.contains(dir.toString()) }, warnings.toString())
  }

  @Test
  fun `ignores output dir that already has jars`() {
    val bundleDir = Files.createTempDirectory("fetch-jars-populated")
    fetchDir(bundleDir, "lib/fetch_jars", dependencyPluginPom(".."))
    Files.writeString(bundleDir.resolve("lib/some-dep.jar"), "")

    assertTrue(findEmptyFetchJarsOutputDirs(bundleDir).isEmpty())
  }

  @Test
  fun `handles nested lib subfolders`() {
    val bundleDir = Files.createTempDirectory("fetch-jars-nested")
    val mysql = fetchDir(bundleDir, "lib/mysql8/fetch_jars", dependencyPluginPom(".."))
    fetchDir(bundleDir, "lib/postgresql/fetch_jars", dependencyPluginPom(".."))
    Files.writeString(bundleDir.resolve("lib/postgresql/postgresql.jar"), "")

    assertEquals(listOf(bundleDir.resolve("lib/mysql8") to mysql), findEmptyFetchJarsOutputDirs(bundleDir))
  }

  @Test
  fun `returns empty when no helper folder present`() {
    val bundleDir = Files.createTempDirectory("fetch-jars-none")
    Files.createDirectories(bundleDir.resolve("src"))

    assertTrue(discoverFetchJarsProjects(bundleDir).isEmpty())
  }

  @Test
  fun `warns when bin includes covers no output dir`() {
    val bundleDir = Files.createTempDirectory("fetch-jars-unpackaged")
    fetchDir(bundleDir, "lib/fetch_jars", dependencyPluginPom(".."))
    Files.writeString(bundleDir.resolve("build.properties"), "bin.includes = META-INF/,.,plugin.xml\n")

    val warnings = captureWarnings { warnOnUnpackagedFetchJarsOutput(bundleDir, discoverFetchJarsProjects(bundleDir), it) }

    assertEquals(1, warnings.size, warnings.toString())
    assertTrue(warnings[0].contains("may not be packaged"))
  }

  @Test
  fun `no warning when output dir covered by bin includes directory entry`() {
    val bundleDir = Files.createTempDirectory("fetch-jars-covered-dir")
    fetchDir(bundleDir, "lib/postgresql/fetch_v422_jars", dependencyPluginPom(".."))
    Files.writeString(bundleDir.resolve("build.properties"), "bin.includes = META-INF/,\\\n  lib/,\\\n  .\n")

    val warnings = captureWarnings { warnOnUnpackagedFetchJarsOutput(bundleDir, discoverFetchJarsProjects(bundleDir), it) }

    assertTrue(warnings.isEmpty(), warnings.toString())
  }

  @Test
  fun `no warning when output dir covered by explicit jar file entry`() {
    val bundleDir = Files.createTempDirectory("fetch-jars-covered-file")
    fetchDir(bundleDir, "libs/fetch_jars", dependencyPluginPom(".."))
    Files.writeString(bundleDir.resolve("build.properties"), "bin.includes = META-INF/,.,libs/foo.jar\n")

    val warnings = captureWarnings { warnOnUnpackagedFetchJarsOutput(bundleDir, discoverFetchJarsProjects(bundleDir), it) }

    assertTrue(warnings.isEmpty(), warnings.toString())
  }

  @Test
  fun `no warning when output dir covered by Bundle-ClassPath`() {
    val bundleDir = Files.createTempDirectory("fetch-jars-covered-cp")
    fetchDir(bundleDir, "lib/fetch_jars", dependencyPluginPom(".."))
    Files.writeString(bundleDir.resolve("build.properties"), "bin.includes = META-INF/,.\n")
    Files.createDirectories(bundleDir.resolve("META-INF"))
    Files.writeString(bundleDir.resolve("META-INF/MANIFEST.MF"), "Manifest-Version: 1.0\nBundle-ClassPath: .,\n lib/foo.jar\n")

    val warnings = captureWarnings { warnOnUnpackagedFetchJarsOutput(bundleDir, discoverFetchJarsProjects(bundleDir), it) }

    assertTrue(warnings.isEmpty(), warnings.toString())
  }

  @Test
  fun `entryCovers semantics`() {
    assertTrue(entryCovers("lib/", "lib/postgresql"))
    assertTrue(entryCovers("libs/foo.jar", "libs"))
    assertTrue(entryCovers("lib/h2/1.4.196/jdbc/h2.jar", "lib/h2/1.4.196/jdbc"))
    assertFalse(entryCovers(".", "lib"))
    assertFalse(entryCovers("META-INF/", "lib"))
    assertFalse(entryCovers("library/", "lib"))
  }
}
