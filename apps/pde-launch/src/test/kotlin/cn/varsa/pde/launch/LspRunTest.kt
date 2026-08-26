package cn.varsa.pde.launch

import cn.varsa.pde.resolver.cli.WorkspaceLiveMarker
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Both refusals below fire before `lsp run` runs `lsp init` or resolves a JDT LS distribution,
 * so no config, cache, or Equinox runtime is needed.
 */
class LspRunTest {
  @Test
  fun `lsp run refuses the pde Equinox workspace as data-dir`() {
    val issueDir = Files.createTempDirectory("lsp-run-refuse")
    val equinoxData = issueDir.resolve(".jdtls/workspace/data")

    val stderr = captureStderr {
      assertEquals(
        2,
        LspRunCommand.main(arrayOf("--issue-dir", issueDir.toString(), "--data-dir", equinoxData.toString()))
      )
    }

    assertTrue(stderr.contains("pde lsp run: --data-dir $equinoxData is the pde Equinox workspace"), stderr)
    assertTrue(stderr.contains("Pass a different --data-dir"), stderr)
    assertFalse(Files.exists(equinoxData), "refusal must not create the directory")
  }

  @Test
  fun `lsp run refuses a data-dir held by another live process`() {
    val issueDir = Files.createTempDirectory("lsp-run-live")
    val dataDir = issueDir.resolve(".lsp").also { Files.createDirectories(it) }
    val self = ProcessHandle.current()
    val start = self.info().startInstant().map { it.toEpochMilli() }.orElse(0L)
    Files.writeString(dataDir.resolve(".pde-live"), "pid=${self.pid()}\nstart=$start\ncommand=pde lsp run\n")

    val stderr = captureStderr {
      assertEquals(2, LspRunCommand.main(arrayOf("--issue-dir", issueDir.toString())))
    }

    assertEquals(
      "pde lsp run: workspace $dataDir is in use by pde lsp run (pid ${self.pid()}); " +
        "stop the other process or pass a different --data-dir",
      stderr.trim()
    )
    assertEquals("pde lsp run", WorkspaceLiveMarker.read(dataDir)?.command)
  }

  private fun captureStderr(block: () -> Unit): String {
    val buffer = ByteArrayOutputStream()
    val original = System.err
    System.setErr(PrintStream(buffer, true, StandardCharsets.UTF_8))
    try {
      block()
    } finally {
      System.setErr(original)
    }
    return buffer.toString(StandardCharsets.UTF_8)
  }
}
