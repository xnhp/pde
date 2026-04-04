package cn.varsa.pde.launch

import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ForeachRepoTest {

  @Test
  fun `foreach-repo loads includes and runs command`() {
    val baseDir = Files.createTempDirectory("foreach-repo")
    val includePath = baseDir.resolve("include.yaml")
    val configPath = baseDir.resolve("pde.yaml")

    Files.writeString(
      includePath,
        """
        bundles:
          - path: repo-a/bundle-a
          - path: repo-b/bundle-b
      """.trimIndent()
    )
    Files.writeString(
      configPath,
      """
        includes:
          - include.yaml
      """.trimIndent()
    )

    val repoA = Files.createDirectories(baseDir.resolve("repo-a"))
    val repoB = Files.createDirectories(baseDir.resolve("repo-b"))
    Files.createDirectories(repoA.resolve("bundle-a"))
    Files.createDirectories(repoB.resolve("bundle-b"))

    val out = ByteArrayOutputStream()
    val savedOut = System.out
    System.setOut(PrintStream(out))
    try {
      val exitCode = ForeachRepoCommand.main(arrayOf("--config", configPath.toString(), "pwd"))
      assertEquals(0, exitCode)
    } finally {
      System.setOut(savedOut)
    }

    val output = out.toString()
    assertTrue(output.contains(repoA.toAbsolutePath().normalize().toString()))
    assertTrue(output.contains(repoB.toAbsolutePath().normalize().toString()))
  }

  @Test
  fun `foreach-repo can suppress repo headers`() {
    val baseDir = Files.createTempDirectory("foreach-repo-headers")
    val configPath = baseDir.resolve("pde.yaml")
    val repoDir = Files.createDirectories(baseDir.resolve("repo-one"))

    Files.writeString(
      configPath,
        """
        bundles:
          - path: repo-one/bundle-one
      """.trimIndent()
    )
    Files.createDirectories(repoDir.resolve("bundle-one"))

    val out = ByteArrayOutputStream()
    val savedOut = System.out
    System.setOut(PrintStream(out))
    try {
      val exitCode = ForeachRepoCommand.main(
        arrayOf("--config", configPath.toString(), "--no-repo-headers", "echo marker")
      )
      assertEquals(0, exitCode)
    } finally {
      System.setOut(savedOut)
    }

    val output = out.toString()
    assertTrue(output.contains("marker"))
    assertFalse(output.contains(repoDir.fileName.toString()))
  }
}
