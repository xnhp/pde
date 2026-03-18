package cn.varsa.pde.launch

import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class WorktreesInitTest {

  @Test
  fun `worktrees-init continues when one worktree path already exists`() {
    val baseDir = Files.createTempDirectory("worktrees-init")
    val baseReposDir = Files.createDirectories(baseDir.resolve("base-repos"))
    val branch = "test-branch"
    Files.createDirectories(baseReposDir.resolve("knime-gateway"))
    Files.createDirectories(baseDir.resolve("knime-gateway"))

    val configPath = baseDir.resolve("pde.yaml")
    Files.writeString(
      configPath,
      """
        branch: $branch
        baseReposPath: base-repos
        bundlesPerRepo:
          - repo: knime-gateway
            bundles:
              - org.knime.gateway.api
          - repo: knime-core
            bundles:
              - org.knime.core
      """.trimIndent()
    )

    val missingRepoPath = baseReposDir.resolve("knime-core").normalize().toString()
    val (exitCode, stderr) = captureStderr {
      WorktreesInitCommand.main(arrayOf("--config", configPath.toString()))
    }
    assertEquals(1, exitCode)
    assertTrue(stderr.contains("Base repo not found: $missingRepoPath"))
  }

  private fun captureStderr(block: () -> Int): Pair<Int, String> {
    val originalErr = System.err
    val buffer = ByteArrayOutputStream()
    val capturing = PrintStream(buffer, true, StandardCharsets.UTF_8)
    return try {
      System.setErr(capturing)
      val exitCode = block()
      exitCode to buffer.toString(StandardCharsets.UTF_8)
    } finally {
      System.setErr(originalErr)
      capturing.flush()
    }
  }
}
