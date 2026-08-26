package cn.varsa.pde.launch

import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class FetchJarsCommandTest {

  @Test
  fun `fetch-jars runs mvn clean package in every fetch_jars dir with a pom`() {
    val originalRunner = fetchJarsCommandRunner
    val baseDir = Files.createTempDirectory("pde-fetch-jars-test")
    val configPath = baseDir.resolve("pde.yaml")
    val bundleDir = Files.createDirectories(baseDir.resolve("repo/org.example.bundle"))
    val fetchJarsDir = Files.createDirectories(bundleDir.resolve("lib/fetch_jars"))
    Files.writeString(fetchJarsDir.resolve("pom.xml"), dependencyPluginPom())
    // A fetch_jars-named dir without a pom.xml should be ignored.
    Files.createDirectories(bundleDir.resolve("other/fetch_jars"))
    // A versioned helper dir must be picked up too.
    val versionedDir = Files.createDirectories(bundleDir.resolve("lib/postgresql/fetch_v422_jars"))
    Files.writeString(versionedDir.resolve("pom.xml"), dependencyPluginPom())

    Files.writeString(
      configPath,
      """
        bundles:
          - path: repo/org.example.bundle
      """.trimIndent()
    )

    val invocations = mutableListOf<Pair<Path, List<String>>>()
    fetchJarsCommandRunner = { workingDir, command ->
      invocations.add(workingDir to command)
      0
    }

    val out = ByteArrayOutputStream()
    val savedOut = System.out
    System.setOut(PrintStream(out))
    try {
      val exitCode = FetchJarsCommand.main(arrayOf("--config", configPath.toString()))
      assertEquals(0, exitCode)
    } finally {
      System.setOut(savedOut)
      fetchJarsCommandRunner = originalRunner
    }

    val normalizedFetchJarsDir = fetchJarsDir.toAbsolutePath().normalize()
    val normalizedVersionedDir = versionedDir.toAbsolutePath().normalize()
    assertEquals(
      listOf(normalizedFetchJarsDir, normalizedVersionedDir).sortedBy { it.toString() }.map { it to listOf("mvn", "clean", "package") },
      invocations
    )
    assertTrue(out.toString().contains(normalizedFetchJarsDir.toString()))
  }

  @Test
  fun `fetch-jars reports when no fetch_jars directories are found`() {
    val originalRunner = fetchJarsCommandRunner
    val baseDir = Files.createTempDirectory("pde-fetch-jars-empty-test")
    val configPath = baseDir.resolve("pde.yaml")
    val bundleDir = Files.createDirectories(baseDir.resolve("repo/org.example.bundle"))
    Files.createDirectories(bundleDir.resolve("src"))

    Files.writeString(
      configPath,
      """
        bundles:
          - path: repo/org.example.bundle
      """.trimIndent()
    )

    fetchJarsCommandRunner = { _, _ -> error("should not be invoked") }

    val out = ByteArrayOutputStream()
    val savedOut = System.out
    System.setOut(PrintStream(out))
    try {
      val exitCode = FetchJarsCommand.main(arrayOf("--config", configPath.toString()))
      assertEquals(0, exitCode)
    } finally {
      System.setOut(savedOut)
      fetchJarsCommandRunner = originalRunner
    }

    assertTrue(out.toString().contains("No fetch-jars helper directories"))
  }

  @Test
  fun `fetch-jars fails when mvn exits non-zero`() {
    val originalRunner = fetchJarsCommandRunner
    val baseDir = Files.createTempDirectory("pde-fetch-jars-fail-test")
    val configPath = baseDir.resolve("pde.yaml")
    val bundleDir = Files.createDirectories(baseDir.resolve("repo/org.example.bundle"))
    val fetchJarsDir = Files.createDirectories(bundleDir.resolve("lib/fetch_jars"))
    Files.writeString(fetchJarsDir.resolve("pom.xml"), dependencyPluginPom())

    Files.writeString(
      configPath,
      """
        bundles:
          - path: repo/org.example.bundle
      """.trimIndent()
    )

    fetchJarsCommandRunner = { _, _ -> 1 }

    val exitCode = try {
      FetchJarsCommand.main(arrayOf("--config", configPath.toString()))
    } finally {
      fetchJarsCommandRunner = originalRunner
    }

    assertEquals(1, exitCode)
  }
}
